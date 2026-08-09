#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JMETER_PLAN="$ROOT_DIR/performance/jmeter/before-index-read.jmx"
RESULT_DIR="$ROOT_DIR/performance/results"
REPORT_DIR="$ROOT_DIR/performance/reports"

mkdir -p "$RESULT_DIR" "$REPORT_DIR"

if [[ -e "$RESULT_DIR/C-warmup.jtl" ]] || \
   [[ -e "$RESULT_DIR/C-index-read-run1.jtl" ]] || \
   [[ -e "$RESULT_DIR/C-index-read-run2.jtl" ]] || \
   [[ -e "$RESULT_DIR/C-index-read-run3.jtl" ]]; then
  echo "C result file already exists. Preserve it or move it before rerunning."
  exit 1
fi

for report in \
  "$REPORT_DIR/C-index-read-run1" \
  "$REPORT_DIR/C-index-read-run2" \
  "$REPORT_DIR/C-index-read-run3"; do
  if [[ -e "$report" ]]; then
    echo "C report directory already exists: $report"
    exit 1
  fi
done

docker compose -f "$ROOT_DIR/compose.perf.yml" ps

jmeter -n \
  -t "$JMETER_PLAN" \
  -Jthreads=1 \
  -Jramp=1 \
  -Jduration=60 \
  -l "$RESULT_DIR/C-warmup.jtl"

for run in 1 2 3; do
  jmeter -n \
    -t "$JMETER_PLAN" \
    -Jthreads=30 \
    -Jramp=30 \
    -Jduration=180 \
    -l "$RESULT_DIR/C-index-read-run${run}.jtl" \
    -e \
    -o "$REPORT_DIR/C-index-read-run${run}"
done

echo "C Custom Principal performance test completed."
