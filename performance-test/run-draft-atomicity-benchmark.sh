#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18080/api/perf/redis/drafts}"
JMX="${JMX:-performance/jmeter/draft-atomicity-comparison.jmx}"
RESULT_ROOT="${RESULT_ROOT:-performance/results/draft-atomicity/benchmark}"
THREADS_PER_SCENARIO="${THREADS_PER_SCENARIO:-10}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
DURATION_SECONDS="${DURATION_SECONDS:-180}"
RUNS="${RUNS:-5}"

mkdir -p "$RESULT_ROOT"

initialize() {
  local draft_id="$1"
  local version="$2"
  local content="$3"
  local dirty="$4"
  jq -nc \
    --argjson id "$draft_id" \
    --argjson version "$version" \
    --arg content "$content" \
    --argjson dirty "$dirty" \
    '{
      draftId: $id,
      title: $content,
      postBody: ("body-" + $content),
      postImage: "",
      contentVersion: $version,
      updatedAt: "2026-08-08T11:00:00",
      dirty: $dirty
    }' |
    curl -fsS -X POST "$BASE_URL/initialize" \
      -H 'Content-Type: application/json' \
      --data-binary @- >/dev/null
}

prepare_run() {
  curl -fsS -X POST "$BASE_URL/reset" >/dev/null
  initialize 201 10 base false
  initialize 202 10 idempotent false
  initialize 203 20 stored false
  initialize 204 20 stored false
  initialize 205 10 dirty true
  docker exec community-perf-redis-1 redis-cli CONFIG RESETSTAT >/dev/null
  docker exec community-perf-redis-1 redis-cli CONFIG SET latency-monitor-threshold 1 >/dev/null
  docker exec community-perf-redis-1 redis-cli LATENCY RESET >/dev/null
}

monitor_containers() {
  local output="$1"
  while true; do
    date -u +%Y-%m-%dT%H:%M:%SZ
    docker stats --no-stream --format \
      '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.NetIO}}' \
      community-perf-backend-1 community-perf-redis-1
    sleep 1
  done >"$output"
}

run_one() {
  local strategy="$1"
  local run_name="$2"
  local duration="$3"
  local directory="$RESULT_ROOT/$strategy/$run_name"
  mkdir -p "$directory"
  prepare_run
  monitor_containers "$directory/docker-stats.csv" &
  local monitor_pid=$!
  trap 'kill "$monitor_pid" 2>/dev/null || true' RETURN

  jmeter -n -t "$JMX" \
    -Jstrategy="$strategy" \
    -JthreadsPerScenario="$THREADS_PER_SCENARIO" \
    -JtotalThreads="$((THREADS_PER_SCENARIO * 5))" \
    -Jramp="$RAMP_SECONDS" \
    -Jduration="$duration" \
    -l "$directory/results.jtl" \
    -j "$directory/jmeter.log"

  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  trap - RETURN
  curl -fsS "$BASE_URL/metrics" >"$directory/application-metrics.json"
  curl -fsS "$BASE_URL/201/state" >"$directory/saved-state.json"
  docker exec community-perf-redis-1 redis-cli INFO commandstats \
    >"$directory/redis-commandstats.txt"
  docker exec community-perf-redis-1 redis-cli INFO cpu \
    >"$directory/redis-cpu.txt"
  docker exec community-perf-redis-1 redis-cli LATENCY LATEST \
    >"$directory/redis-latency.txt"
}

for strategy in LUA WATCH RLOCK; do
  echo "Warm-up: $strategy"
  run_one "$strategy" warmup "$WARMUP_SECONDS"
done

for run in $(seq 1 "$RUNS"); do
  case "$run" in
    1) order=(LUA WATCH RLOCK) ;;
    2) order=(WATCH RLOCK LUA) ;;
    3) order=(RLOCK LUA WATCH) ;;
    4) order=(LUA RLOCK WATCH) ;;
    *) order=(WATCH LUA RLOCK) ;;
  esac
  for strategy in "${order[@]}"; do
    echo "Measured run $run: $strategy"
    run_one "$strategy" "run-$run" "$DURATION_SECONDS"
  done
done
