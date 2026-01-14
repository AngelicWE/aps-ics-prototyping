import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MemoryMapWriteBurst
 *
 * Burst-mode timing probe for the HCD copy path.
 * Writes MANY frames into ONE container file using memory-mapped I/O
 * with <= 2 GiB windows. Each frame uses a single bulk put(...).
 *
 * Updated methodology:
 *   1) Prefault (optional): READ_ONLY page-touch across the whole container (no dirty pages)
 *   2) Warmup (NEW): mapping-only warmup across all windows (READ_WRITE map/unmap, no writes)
 *   3) Warmup (NEW, optional): minimal dirty warmup (write 1 page per window; e.g., 4096 bytes)
 *   4) CPU warmup spin
 *   5) SINGLE measured burst write per trial (no full-size burn-in write)
 *   6) force()+optional sleep after the measured burst
 *
 * Usage:
 *   java MemoryMapWriteBurst [width height frameCount [outDir] [bytesPerPixel] [pattern] [trials]]
 *     width/height   ROI in pixels (default 1020×1020)
 *     frameCount     number of frames (default 1000)
 *     outDir         output directory (default /tmp/aps)
 *     bytesPerPixel  1=8-bit, 2=16-bit, 4=32-bit (default 2)
 *     pattern        { zero | random | ramp | alt } (default random)
 *     trials         number of timed trials/files to generate (default 1)
 */
public class MemoryMapWriteBurst {
  // Defaults
  static final int  DEFAULT_WIDTH  = 1020;
  static final int  DEFAULT_HEIGHT = 1020;
  static final int  DEFAULT_FRAMES = 1000;
  static final int  DEFAULT_BPP    = 2;
  static final long WINDOW_BYTES   = 2_000_000_000L;

  // Per-trial isolation & pacing (no CLI)
  private static final boolean ROTATE_PATHS        = true;  // create a fresh file per trial

  // Optional stabilization
  private static final boolean ENABLE_PREFAULT     = false;  // read-only page-touch (no dirty pages)
  private static final boolean ENABLE_MAP_WARMUP   = true;  // NEW: map/unmap each window READ_WRITE (no write)
  private static final boolean ENABLE_DIRTY_WARMUP = true;  // NEW: dirty only a small amount per window
  private static final int     DIRTY_WARMUP_BYTES  = 4096;  // NEW: dirty 1 page per window (<= len)

  private static final int     CPU_WARMUP_MS       = 150;   // small spin to stabilize clocks/JIT

  // Post-trial drain (keep visible to diagnose writeback pressure)
  private static final boolean FORCE_FLUSH_AFTER_TRIAL = false;
  private static final int     SLEEP_AFTER_TRIAL_MS    = 0;

  public static void main(String[] args) throws IOException {
    if (args.length == 1 || args.length == 2 || args.length > 7) {
      System.err.println("Usage: java MemoryMapWriteBurst [width height frameCount [outDir] [bytesPerPixel] [pattern] [trials]]");
      System.exit(2);
    }

    int  width  = DEFAULT_WIDTH;
    int  height = DEFAULT_HEIGHT;
    int  frames = DEFAULT_FRAMES;
    Path outDir = Paths.get("/tmp", "aps");
    int  bpp    = DEFAULT_BPP;
    String pattern = "random";
    int  trials = 1;

    if (args.length >= 3) { width = parsePos(args[0], "width"); height = parsePos(args[1], "height"); frames = parsePos(args[2], "frameCount"); }
    if (args.length >= 4) outDir  = Paths.get(args[3]);
    if (args.length >= 5) bpp     = parsePos(args[4], "bytesPerPixel");
    if (args.length >= 6) pattern = args[5].toLowerCase();
    if (args.length == 7) trials  = parsePos(args[6], "trials");

    Files.createDirectories(outDir);

    final int  frameBytes     = Math.multiplyExact(Math.multiplyExact(width, height), bpp);
    if ((long) frameBytes > WINDOW_BYTES)
      throw new IllegalArgumentException("frameBytes exceeds window size (" + WINDOW_BYTES + ")");
    final long containerBytes = Math.multiplyExact((long) frameBytes, (long) frames);

    // Source buffer filled once (outside timing)
    final byte[] src = new byte[frameBytes];
    fill(src, pattern);

    System.out.printf("Burst -> %dx%d @ %d B/px (%s), %d frames, container %,d bytes%n",
        width, height, bpp, pattern, frames, containerBytes);
    System.out.printf("Warmup: prefault=%s, mapWarmup=%s, dirtyWarmup=%s (%d bytes/window), cpuWarmupMs=%d%n",
        ENABLE_PREFAULT, ENABLE_MAP_WARMUP, ENABLE_DIRTY_WARMUP, DIRTY_WARMUP_BYTES, CPU_WARMUP_MS);

    // Per-trial totals
    List<Double> copyMsList     = new ArrayList<>(trials);
    List<Double> end2endMsList  = new ArrayList<>(trials);
    List<Double> remapMsList    = new ArrayList<>(trials);
    List<Integer> remapsList    = new ArrayList<>(trials);
    List<Double> forceMsList    = new ArrayList<>(trials);

    for (int t = 1; t <= trials; t++) {
      Path path = outDir.resolve(String.format(
          ROTATE_PATHS
              ? "burst_container_%dx%d_%dbpp_%dframes_trial%02d.bin"
              : "burst_container_%dx%d_%dbpp_%dframes.bin",
          width, height, bpp * 8, frames, t));

      try (FileChannel ch = FileChannel.open(path,
          StandardOpenOption.CREATE, StandardOpenOption.READ,
          StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

        ch.truncate(containerBytes);

        // -------------------- Prefault (READ_ONLY page-touch) --------------------
        if (ENABLE_PREFAULT) {
          prefaultReadOnly(ch, containerBytes);
        }

        // -------------------- Warmup: mapping-only across windows (no writes) --------------------
        if (ENABLE_MAP_WARMUP) {
          warmupMappingsOnly(ch, containerBytes);
        }

        // -------------------- Warmup: minimal dirty write per window --------------------
        if (ENABLE_DIRTY_WARMUP) {
          warmupDirtyOnePagePerWindow(ch, containerBytes, DIRTY_WARMUP_BYTES);
        }

        // Short CPU warm-up
        cpuWarmupMillis(CPU_WARMUP_MS);

        // -------------------- SINGLE measured burst --------------------
        TrialResult r = runOneTimedTrial(ch, src, frameBytes, containerBytes);

        copyMsList.add(r.copyMs);
        end2endMsList.add(r.endToEndMs);
        remapMsList.add(r.remapMs);
        remapsList.add(r.remaps);

        // Drain after measured trial (optional) + always record force() time for diagnostics
        double forceMs = forceAndTime(ch);
        forceMsList.add(forceMs);

        System.out.printf("Trial %d/%d: copy=%.3f ms | end-to-end=%.3f ms | remap=%.3f ms | remaps=%d | force()=%.3f ms -> %s%n",
            t, trials, r.copyMs, r.endToEndMs, r.remapMs, r.remaps, forceMs, path.toAbsolutePath());

        if (FORCE_FLUSH_AFTER_TRIAL) {
          // force() already called above; this flag is preserved for legacy toggling semantics
        }
        if (SLEEP_AFTER_TRIAL_MS > 0) sleepMs(SLEEP_AFTER_TRIAL_MS);
      }
    }

    // Summaries (after all trials/files)
    summarize("copy ms",     copyMsList);
    summarize("end-to-end",  end2endMsList);
    summarize("remap ms",    remapMsList);
    summarize("force ms",    forceMsList);

    int[] remArr = remapsList.stream().mapToInt(Integer::intValue).toArray();
    System.out.println("Remaps (count): min=" + Arrays.stream(remArr).min().orElse(0) +
        " max=" + Arrays.stream(remArr).max().orElse(0) +
        " mean=" + String.format("%.2f", mean(remArr)));
  }

  // -------------------- Prefault (READ_ONLY) --------------------
  private static void prefaultReadOnly(FileChannel ch, long containerBytes) throws IOException {
    final int page = 4096;
    for (long start = 0; start < containerBytes; start += WINDOW_BYTES) {
      int len = (int) Math.min(WINDOW_BYTES, containerBytes - start);
      MappedByteBuffer ro = ch.map(FileChannel.MapMode.READ_ONLY, start, len);
      for (int p = 0; p < len; p += page) {
        byte b = ro.get(p);
        if ((b & 1) == 2) System.out.print(""); // keep JIT from eliding
      }
      if ((len % page) != 0) {
        byte b = ro.get(len - 1);
        if ((b & 1) == 2) System.out.print("");
      }
    }
  }

  // -------------------- Warmup: map/unmap windows (READ_WRITE, no writes) --------------------
  private static void warmupMappingsOnly(FileChannel ch, long containerBytes) throws IOException {
    for (long start = 0; start < containerBytes; start += WINDOW_BYTES) {
      int len = (int) Math.min(WINDOW_BYTES, containerBytes - start);
      MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_WRITE, start, len);
      // Touch a couple of positions via reads only (still non-dirty) to avoid complete elision.
      // Reading from READ_WRITE mapping does not dirty pages.
      byte b0 = map.get(0);
      byte b1 = map.get(len - 1);
      if (((b0 ^ b1) & 1) == 2) System.out.print("");
      // Let map go out of scope; GC/unmap timing is OS-dependent, but warmup still exercises mapping path.
    }
  }

  // -------------------- Warmup: dirty only one small page per window --------------------
  private static void warmupDirtyOnePagePerWindow(FileChannel ch, long containerBytes, int dirtyBytes) throws IOException {
    if (dirtyBytes <= 0) return;

    byte[] tiny = new byte[Math.min(dirtyBytes, 4096)];
    // Deterministic, non-zero pattern
    for (int i = 0; i < tiny.length; i++) tiny[i] = (byte) (i * 31 + 7);

    for (long start = 0; start < containerBytes; start += WINDOW_BYTES) {
      int len = (int) Math.min(WINDOW_BYTES, containerBytes - start);
      MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_WRITE, start, len);
      int n = Math.min(tiny.length, len);
      map.position(0);
      map.put(tiny, 0, n); // dirties only first page(s) of each window
    }
  }

  // -------------------- Core timed trial (fixed windowing logic) --------------------
  private static TrialResult runOneTimedTrial(FileChannel ch, byte[] src,
                                              int frameBytes, long containerBytes) throws IOException {
    long copyNsTotal  = 0L;
    long remapNsTotal = 0L;
    long end2endStart = System.nanoTime();

    int remaps = 0;
    final int totalFrames = (int) (containerBytes / frameBytes);

    MappedByteBuffer map = null;
    long currentWindowStart = -1;
    int currentWindowLen = 0;

    for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
      long frameOffset = (long) frameIndex * frameBytes;
      long frameEnd = frameOffset + frameBytes;

      boolean needsRemap = (map == null) ||
                          (frameOffset < currentWindowStart) ||
                          (frameEnd > currentWindowStart + currentWindowLen);

      if (needsRemap) {
        long windowStart = frameOffset;
        long maxWindowEnd = Math.min(windowStart + WINDOW_BYTES, containerBytes);
        int windowLen = (int) (maxWindowEnd - windowStart);

        long t0 = System.nanoTime();
        map = ch.map(FileChannel.MapMode.READ_WRITE, windowStart, windowLen);
        map.position(0);
        long t1 = System.nanoTime();

        currentWindowStart = windowStart;
        currentWindowLen = windowLen;
        remaps++;
        remapNsTotal += (t1 - t0);
      }

      long c0 = System.nanoTime();
      map.put(src);
      long c1 = System.nanoTime();
      copyNsTotal += (c1 - c0);
    }

    long end2endEnd = System.nanoTime();
    return new TrialResult(
        copyNsTotal / 1e6,
        (end2endEnd - end2endStart) / 1e6,
        remapNsTotal / 1e6,
        remaps
    );
  }

  private static class TrialResult {
    final double copyMs, endToEndMs, remapMs;
    final int remaps;
    TrialResult(double c, double e, double r, int m){ copyMs=c; endToEndMs=e; remapMs=r; remaps=m; }
  }

  // -------------------- Utilities --------------------
  private static int parsePos(String s, String name) {
    int v = Integer.parseInt(s);
    if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
    return v;
  }

  private static void fill(byte[] a, String pattern) {
    switch (pattern) {
      case "zero": /* leave zeros */ break;
      case "alt":  for (int i = 0; i < a.length; i++) a[i] = (byte)((i & 1) == 0 ? 0x55 : 0xAA); break;
      case "ramp": for (int i = 0; i < a.length; i++) a[i] = (byte)i; break;
      case "random":
      default: ThreadLocalRandom.current().nextBytes(a); break;
    }
  }

  private static void cpuWarmupMillis(int ms) {
    long end = System.nanoTime() + (long)ms * 1_000_000L;
    long x = 0;
    while (System.nanoTime() < end) { x += 31; x ^= (x << 7); }
    if (x == 42) System.out.print("");
  }

  private static double forceAndTime(FileChannel ch) {
    long t0 = System.nanoTime();
    try { ch.force(true); } catch (IOException ignored) {}
    long t1 = System.nanoTime();
    return (t1 - t0) / 1e6;
  }

  private static void sleepMs(int ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }

  // -------------------- Stats helpers --------------------
  private static void summarize(String label, List<Double> vals) {
    if (vals.isEmpty()) {
      System.out.printf("Summary (%s): no data%n", label);
      return;
    }
    double[] a = vals.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(a);
    double mn = a[0], mx = a[a.length - 1];
    double mean = mean(a);
    double med  = percentile(a, 50);
    double sd   = stddev(a, mean);
    double q1 = percentile(a, 25), q3 = percentile(a, 75);
    double iqr = q3 - q1, lo = q1 - 1.5 * iqr, hi = q3 + 1.5 * iqr;
    List<Integer> outIdx = new ArrayList<>();
    for (int i = 0; i < a.length; i++) if (a[i] < lo || a[i] > hi) outIdx.add(i + 1);
    System.out.printf("Summary (%s): min=%.3f ms | max=%.3f ms | mean=%.3f ms | median=%.3f ms | std=%.3f ms%n",
        label, mn, mx, mean, med, sd);
    if (!outIdx.isEmpty()) System.out.printf("  Outliers by IQR at trials %s%n", outIdx);
  }

  private static double mean(double[] a) { double s=0; for(double v:a) s+=v; return s/a.length; }
  private static double mean(int[] a)    { double s=0; for(int v:a) s+=v; return s/(double)a.length; }
  private static double percentile(double[] sorted, double p) {
    if (sorted.length == 0) return Double.NaN;
    double rank = (p / 100.0) * (sorted.length - 1);
    int lo = (int)Math.floor(rank), hi = (int)Math.ceil(rank);
    if (lo == hi) return sorted[lo];
    double w = rank - lo; return sorted[lo]*(1-w) + sorted[hi]*w;
  }
  private static double stddev(double[] a, double mean) {
    double s=0; for(double v:a){ double d=v-mean; s+=d*d; } return Math.sqrt(s/a.length);
  }
}