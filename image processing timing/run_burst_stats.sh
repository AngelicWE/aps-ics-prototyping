#!/usr/bin/env bash
set -euo pipefail

# ---- Config (edit as needed) ----
JAVA_CMD=${JAVA_CMD:-java}                 # e.g. "java" or "/path/to/java"
CLASS=${CLASS:-MemoryMapWriteBurst}        # class name (no .java)
WIDTH=${WIDTH:-2048}
HEIGHT=${HEIGHT:-2048}
FRAMES=${FRAMES:-1000}
OUTDIR=${OUTDIR:-/tmp/aps}
BPP=${BPP:-2}
PATTERN=${PATTERN:-random}
N=${N:-10}                                 # number of separate-process runs

# Pause between runs to let background writeback settle (set 0 for stress)
SLEEP_BETWEEN=${SLEEP_BETWEEN:-3}

mkdir -p "$OUTDIR"
TS=$(date +"%Y%m%d_%H%M%S")
CSV="$OUTDIR/burst_stats_${WIDTH}x${HEIGHT}_${BPP}bpp_${FRAMES}frames_${TS}.csv"
LOG="$OUTDIR/burst_stats_${WIDTH}x${HEIGHT}_${BPP}bpp_${FRAMES}frames_${TS}.log"

# Container size (bytes) = width * height * bpp * frames
# Use awk for 64-bit safety on macOS bash
CONTAINER_BYTES=$(awk -v w="$WIDTH" -v h="$HEIGHT" -v b="$BPP" -v f="$FRAMES" 'BEGIN{printf "%.0f", w*h*b*f}')
CONTAINER_MB=$(awk -v cb="$CONTAINER_BYTES" 'BEGIN{printf "%.3f", cb/1e6}')

echo "Container: ${CONTAINER_BYTES} bytes (${CONTAINER_MB} MB)"
echo "Writing CSV to: $CSV"
echo "Full logs to:   $LOG"
echo

echo "run,copy_ms,end2end_ms,remap_ms,remaps,force_ms,throughput_MBps,throughput_durable_MBps,path" > "$CSV"

for i in $(seq 1 "$N"); do
  echo "=== Run $i/$N ===" | tee -a "$LOG"

  # NOTE: assumes you've already compiled and are running from the directory containing .class
  OUT="$($JAVA_CMD -cp . "$CLASS" "$WIDTH" "$HEIGHT" "$FRAMES" "$OUTDIR" "$BPP" "$PATTERN" 1 2>&1 | tee -a "$LOG")"

  LINE=$(echo "$OUT" | grep -E "Trial 1/1:" | tail -n 1)

  if [[ -z "$LINE" ]]; then
    echo "ERROR: Could not parse Trial line in run $i. See log: $LOG" | tee -a "$LOG"
    exit 1
  fi

  copy_ms=$(echo "$LINE" | sed -E 's/.*copy=([0-9.]+) ms.*/\1/')
  end2end_ms=$(echo "$LINE" | sed -E 's/.*end-to-end=([0-9.]+) ms.*/\1/')
  remap_ms=$(echo "$LINE" | sed -E 's/.*remap=([0-9.]+) ms.*/\1/')
  remaps=$(echo "$LINE" | sed -E 's/.*remaps=([0-9]+).*/\1/')
  force_ms=$(echo "$LINE" | sed -E 's/.*force\(\)=([0-9.]+) ms.*/\1/')
  path=$(echo "$LINE" | sed -E 's/.*-> (.*)$/\1/')

  # Buffered throughput (MB/s) = containerBytes / (end2endSeconds) / 1e6
  throughput=$(awk -v cb="$CONTAINER_BYTES" -v ms="$end2end_ms" \
    'BEGIN{ if(ms<=0){print "nan"} else { printf "%.3f", (cb/(ms/1000.0))/1e6 } }')

  # Durable throughput (MB/s) = containerBytes / ((end2end+force)Seconds) / 1e6
  throughput_durable=$(awk -v cb="$CONTAINER_BYTES" -v e="$end2end_ms" -v f="$force_ms" \
    'BEGIN{ t=(e+f)/1000.0; if(t<=0){print "nan"} else { printf "%.3f", (cb/t)/1e6 } }')

  echo "$i,$copy_ms,$end2end_ms,$remap_ms,$remaps,$force_ms,$throughput,$throughput_durable,\"$path\"" >> "$CSV"

  if [[ "$SLEEP_BETWEEN" != "0" ]]; then
    sleep "$SLEEP_BETWEEN"
  fi
done

echo
echo "Done."
echo "CSV: $CSV"
echo "LOG: $LOG"
echo

# ---- Stats printer (awk / macOS compatible) ----
# Prints: n, min, max, mean, std, p50, p95
stats() {
  local col="$1"
  local label="$2"

  awk -F, -v COL="$col" -v LABEL="$label" '
    # Percentile helper (linear interpolation). Expects sorted array a[1..n].
    function pct(p,    rank, lo, hi, w){
      if(n==1) return a[1]
      rank = (p/100.0) * (n-1) + 1
      lo = int(rank)
      hi = lo + 1
      if(hi>n) return a[n]
      w = rank - lo
      return a[lo]*(1-w) + a[hi]*w
    }

    NR==1 { next }  # skip header

    {
      v = $COL + 0
      n++
      a[n] = v
      sum += v
      sumsq += v*v
      if(n==1 || v < min) min = v
      if(n==1 || v > max) max = v
    }

    END {
      if(n==0){ printf "%s: no data\n", LABEL; exit }

      mean = sum / n
      var  = (sumsq / n) - (mean*mean)
      if(var < 0) var = 0
      std  = sqrt(var)

      # sort a[1..n] (simple O(n^2), fine for small N)
      for(i=1;i<=n;i++){
        for(j=i+1;j<=n;j++){
          if(a[j] < a[i]){
            t=a[i]; a[i]=a[j]; a[j]=t
          }
        }
      }

      p50 = pct(50)
      p95 = pct(95)

      printf "%s: n=%d | min=%.3f | max=%.3f | mean=%.3f | std=%.3f | p50=%.3f | p95=%.3f\n",
             LABEL, n, min, max, mean, std, p50, p95
    }
  ' "$CSV"
}

echo "=== Summary statistics (${WIDTH}x${HEIGHT}, ${BPP} B/px, ${FRAMES} frames; ${CONTAINER_MB} MB) ==="
stats 3 "end2end_ms"
stats 2 "copy_ms"
stats 6 "force_ms"
stats 7 "throughput_MBps"
stats 8 "throughput_durable_MBps"