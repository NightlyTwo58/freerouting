#!/usr/bin/env bash
#
# benchmark_freerouting.sh
#
# Benchmarks Freerouting CLI runs over every .dsn file in /benchmarks,
# capturing wall-clock time + CPU time (via hyperfine) and peak memory
# (via /usr/bin/time -v, since hyperfine itself doesn't track RAM).
# Also extracts the Freerouting routing "score" from each run's log output.
#
# Outputs:
#   - One hyperfine CSV per board:    results/<board>.hyperfine.csv
#   - A combined summary CSV:         results/summary.csv
#   - A CLI table printed at the end
#
# Requirements: hyperfine, /usr/bin/time (GNU time, "time -v"), bc
#
# Usage:
#   ./benchmark_freerouting.sh [path-to-freerouting-jar] [runs]
#
# Example:
#   ./benchmark_freerouting.sh freerouting-2.2.2.jar 5

set -euo pipefail

JAR="${1:-freerouting-2.2.2.jar}"
RUNS="${2:-3}"
BOARD_DIR="/benchmarks"
OUT_DIR="$(pwd)/results"
LOG_DIR="${OUT_DIR}/logs"
SUMMARY_CSV="${OUT_DIR}/summary.csv"

mkdir -p "$OUT_DIR" "$LOG_DIR"

# Sanity checks
command -v hyperfine >/dev/null 2>&1 || { echo "ERROR: hyperfine not found. Install it first." >&2; exit 1; }
command -v /usr/bin/time >/dev/null 2>&1 || { echo "ERROR: /usr/bin/time (GNU time) not found." >&2; exit 1; }
[ -f "$JAR" ] || { echo "ERROR: Freerouting jar not found at '$JAR'" >&2; exit 1; }
[ -d "$BOARD_DIR" ] || { echo "ERROR: Board directory '$BOARD_DIR' not found" >&2; exit 1; }

shopt -s nullglob
boards=("$BOARD_DIR"/*.dsn)
shopt -u nullglob

if [ ${#boards[@]} -eq 0 ]; then
    echo "ERROR: No .dsn files found in $BOARD_DIR" >&2
    exit 1
fi

# CSV header for the combined summary
echo "board,mean_time_s,stddev_time_s,min_time_s,max_time_s,user_cpu_s,system_cpu_s,peak_memory_kb,score" > "$SUMMARY_CSV"

for board in "${boards[@]}"; do
    name="$(basename "$board" .dsn)"
    echo "=== Benchmarking: $name ==="

    board_csv="${OUT_DIR}/${name}.hyperfine.csv"
    ses_out="${LOG_DIR}/${name}.ses"
    time_log="${LOG_DIR}/${name}.time.log"
    fr_log="${LOG_DIR}/${name}.freerouting.log"

    # The command hyperfine will time. We route /usr/bin/time -v output to a
    # separate file each run so we can pull peak RSS afterward, and also
    # capture Freerouting's stdout/stderr to extract the score.
    cmd="/usr/bin/time -v -o '${time_log}' \
        java -jar '${JAR}' \
        -de '${board}' \
        -do '${ses_out}' \
        --gui.enabled=false \
        --logging.file.location='${LOG_DIR}' \
        > '${fr_log}' 2>&1"

    hyperfine \
        --warmup 0 \
        --runs "$RUNS" \
        --export-csv "$board_csv" \
        --show-output \
        "$cmd" || echo "WARNING: hyperfine reported a non-zero exit for $name"

    # --- Parse timing stats from hyperfine's CSV (mean/stddev/min/max, seconds) ---
    # hyperfine CSV columns: command,mean,stddev,median,user,system,min,max
    read -r mean_time stddev_time min_time max_time user_cpu sys_cpu < <(
        tail -n 1 "$board_csv" | awk -F',' '{print $2, $3, $7, $8, $5, $6}'
    )

    # --- Parse peak memory (KB) from the LAST /usr/bin/time -v run for this board ---
    # /usr/bin/time -v appends each run; grab the last "Maximum resident set size"
    peak_mem_kb=$(grep "Maximum resident set size" "$time_log" | tail -n 1 | awk -F': ' '{print $2}')
    peak_mem_kb="${peak_mem_kb:-NA}"

    # --- Parse Freerouting's routing score from its log/stdout ---
    # Freerouting typically logs a line like: "Final score: 123.45" or similar.
    # Adjust the grep pattern below if your Freerouting version logs differently.
    score=$(grep -Eio '(final[ _]?score|score)[: ]+[0-9]+(\.[0-9]+)?' "$fr_log" | tail -n 1 | grep -Eo '[0-9]+(\.[0-9]+)?$')
    score="${score:-NA}"

    echo "${name},${mean_time},${stddev_time},${min_time},${max_time},${user_cpu},${sys_cpu},${peak_mem_kb},${score}" >> "$SUMMARY_CSV"
done

echo ""
echo "=== Done. Combined CSV written to: ${SUMMARY_CSV} ==="
echo ""

# --- Print a clean CLI table from the summary CSV ---
if command -v column >/dev/null 2>&1; then
    {
        echo "Board,Mean(s),StdDev(s),Min(s),Max(s),UserCPU(s),SysCPU(s),PeakMem(KB),Score"
        tail -n +2 "$SUMMARY_CSV"
    } | column -t -s ','
else
    cat "$SUMMARY_CSV"
fi
