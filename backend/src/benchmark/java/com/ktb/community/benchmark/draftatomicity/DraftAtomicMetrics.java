package com.ktb.community.benchmark.draftatomicity;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

@Component
@Profile("perf")
public class DraftAtomicMetrics {

    private final Map<DraftAtomicStrategy, StrategyMetrics> metrics =
            new EnumMap<>(DraftAtomicStrategy.class);

    public DraftAtomicMetrics() {
        for (DraftAtomicStrategy strategy : DraftAtomicStrategy.values()) {
            metrics.put(strategy, new StrategyMetrics());
        }
    }

    public void record(
            DraftAtomicStrategy strategy,
            DraftAtomicResult result
    ) {
        metrics.get(strategy).record(result);
    }

    public void recordDirty(
            DraftAtomicStrategy strategy,
            boolean removed,
            long operationNanos
    ) {
        metrics.get(strategy).recordDirty(removed, operationNanos);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        metrics.forEach((strategy, value) ->
                result.put(strategy.name(), value.snapshot()));
        return result;
    }

    public void reset() {
        metrics.values().forEach(StrategyMetrics::reset);
    }

    private static final class StrategyMetrics {
        private final Map<DraftAtomicStatus, LongAdder> statuses =
                new EnumMap<>(DraftAtomicStatus.class);
        private LongAdder operations = new LongAdder();
        private LongAdder totalOperationNanos = new LongAdder();
        private LongAdder totalAttempts = new LongAdder();
        private LongAdder totalLockWaitNanos = new LongAdder();
        private LongAccumulator maxAttempts =
                new LongAccumulator(Long::max, 0L);
        private LongAdder dirtyOperations = new LongAdder();
        private LongAdder dirtyRemoved = new LongAdder();
        private LongAdder totalDirtyOperationNanos = new LongAdder();

        private StrategyMetrics() {
            for (DraftAtomicStatus status : DraftAtomicStatus.values()) {
                statuses.put(status, new LongAdder());
            }
        }

        private void record(DraftAtomicResult result) {
            operations.increment();
            totalOperationNanos.add(result.operationNanos());
            totalAttempts.add(result.attempts());
            totalLockWaitNanos.add(result.lockWaitNanos());
            maxAttempts.accumulate(result.attempts());
            statuses.get(result.status()).increment();
        }

        private void recordDirty(boolean removed, long operationNanos) {
            dirtyOperations.increment();
            if (removed) {
                dirtyRemoved.increment();
            }
            totalDirtyOperationNanos.add(operationNanos);
        }

        private Map<String, Object> snapshot() {
            long count = operations.sum();
            Map<String, Long> statusCounts = new LinkedHashMap<>();
            statuses.forEach((status, value) ->
                    statusCounts.put(status.name(), value.sum()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("operations", count);
            result.put("statusCounts", statusCounts);
            result.put("averageOperationMicros", count == 0
                    ? 0D
                    : totalOperationNanos.sum() / 1_000D / count);
            result.put("averageAttempts", count == 0
                    ? 0D
                    : (double) totalAttempts.sum() / count);
            result.put("maxAttempts", maxAttempts.get());
            result.put("averageLockWaitMicros", count == 0
                    ? 0D
                    : totalLockWaitNanos.sum() / 1_000D / count);
            long dirtyCount = dirtyOperations.sum();
            result.put("dirtyOperations", dirtyCount);
            result.put("dirtyRemoved", dirtyRemoved.sum());
            result.put("averageDirtyOperationMicros", dirtyCount == 0
                    ? 0D
                    : totalDirtyOperationNanos.sum()
                    / 1_000D / dirtyCount);
            return result;
        }

        private void reset() {
            operations = new LongAdder();
            totalOperationNanos = new LongAdder();
            totalAttempts = new LongAdder();
            totalLockWaitNanos = new LongAdder();
            maxAttempts = new LongAccumulator(Long::max, 0L);
            dirtyOperations = new LongAdder();
            dirtyRemoved = new LongAdder();
            totalDirtyOperationNanos = new LongAdder();
            statuses.replaceAll((ignored, value) -> new LongAdder());
        }
    }
}
