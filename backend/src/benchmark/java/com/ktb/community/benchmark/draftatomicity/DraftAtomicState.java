package com.ktb.community.benchmark.draftatomicity;

public record DraftAtomicState(
        Long draftId,
        boolean exists,
        DraftAtomicSnapshot snapshot,
        Long ttlSeconds,
        boolean dirty,
        Double dirtyScore
) {
}
