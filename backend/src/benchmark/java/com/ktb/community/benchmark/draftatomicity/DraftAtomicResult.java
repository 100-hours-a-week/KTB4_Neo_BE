package com.ktb.community.benchmark.draftatomicity;

public record DraftAtomicResult(
        DraftAtomicStatus status,
        DraftAtomicSnapshot snapshot,
        int attempts,
        long lockWaitNanos,
        long operationNanos
) {
    public DraftAtomicResult withOperationNanos(long nanos) {
        return new DraftAtomicResult(
                status,
                snapshot,
                attempts,
                lockWaitNanos,
                nanos
        );
    }
}
