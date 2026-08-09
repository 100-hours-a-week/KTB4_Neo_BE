#!/usr/bin/env python3

import csv
import json
import math
import statistics
from pathlib import Path


ROOT = Path(__file__).parent / "results" / "draft-atomicity" / "benchmark"
STRATEGIES = ("LUA", "WATCH", "RLOCK")


def percentile(values, percentile_value):
    ordered = sorted(values)
    if not ordered:
        return 0
    rank = (len(ordered) - 1) * percentile_value
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def parse_percent(value):
    return float(value.rstrip("%"))


def run_summary(strategy, run_number):
    directory = ROOT / strategy / f"run-{run_number}"
    rows = list(csv.DictReader((directory / "results.jtl").open()))
    elapsed = [int(row["elapsed"]) for row in rows]
    timestamps = [int(row["timeStamp"]) for row in rows]
    errors = sum(row["success"].lower() != "true" for row in rows)
    wall_seconds = (max(timestamps) - min(timestamps) + max(elapsed)) / 1000

    labels = {}
    for row in rows:
        label = row["label"]
        bucket = labels.setdefault(label, {"count": 0, "errors": 0, "elapsed": []})
        bucket["count"] += 1
        bucket["errors"] += row["success"].lower() != "true"
        bucket["elapsed"].append(int(row["elapsed"]))

    metrics = json.loads((directory / "application-metrics.json").read_text())[strategy]
    state = json.loads((directory / "saved-state.json").read_text())

    cpu_rows = []
    stats_path = directory / "docker-stats.csv"
    if stats_path.exists():
        lines = stats_path.read_text().splitlines()
        for index in range(0, len(lines) - 2, 3):
            for line in lines[index + 1:index + 3]:
                parts = line.split(",")
                if len(parts) >= 2:
                    cpu_rows.append((parts[0], parse_percent(parts[1])))
    redis_cpu = [cpu for name, cpu in cpu_rows if name == "community-perf-redis-1"]
    backend_cpu = [cpu for name, cpu in cpu_rows if name == "community-perf-backend-1"]

    label_summaries = {}
    for label, bucket in labels.items():
        values = bucket.pop("elapsed")
        bucket.update({
            "averageMs": statistics.fmean(values),
            "p95Ms": percentile(values, 0.95),
            "p99Ms": percentile(values, 0.99),
        })
        label_summaries[label] = bucket

    return {
        "strategy": strategy,
        "run": run_number,
        "requests": len(rows),
        "wallSeconds": wall_seconds,
        "tps": len(rows) / wall_seconds,
        "averageMs": statistics.fmean(elapsed),
        "p50Ms": percentile(elapsed, 0.50),
        "p95Ms": percentile(elapsed, 0.95),
        "p99Ms": percentile(elapsed, 0.99),
        "maxMs": max(elapsed),
        "errors": errors,
        "errorRate": errors / len(rows),
        "labels": label_summaries,
        "applicationMetrics": metrics,
        "savedState": state,
        "averageRedisCpuPercent": statistics.fmean(redis_cpu) if redis_cpu else 0,
        "averageBackendCpuPercent": statistics.fmean(backend_cpu) if backend_cpu else 0,
    }


def aggregate(strategy, runs):
    scalar_fields = (
        "requests", "tps", "averageMs", "p50Ms", "p95Ms", "p99Ms", "maxMs",
        "errors", "errorRate", "averageRedisCpuPercent", "averageBackendCpuPercent",
    )
    result = {"strategy": strategy, "runs": len(runs)}
    for field in scalar_fields:
        values = [run[field] for run in runs]
        result[field + "Mean"] = statistics.fmean(values)
        result[field + "Median"] = statistics.median(values)
        result[field + "Min"] = min(values)
        result[field + "Max"] = max(values)
        if len(values) > 1:
            result[field + "Stddev"] = statistics.stdev(values)
    return result


def main():
    all_runs = []
    aggregates = []
    for strategy in STRATEGIES:
        runs = [run_summary(strategy, run) for run in range(1, 6)]
        all_runs.extend(runs)
        aggregates.append(aggregate(strategy, runs))
    output = {"runs": all_runs, "aggregates": aggregates}
    (ROOT / "summary.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(aggregates, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
