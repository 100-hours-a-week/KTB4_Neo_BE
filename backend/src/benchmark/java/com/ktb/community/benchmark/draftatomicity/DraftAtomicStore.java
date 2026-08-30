package com.ktb.community.benchmark.draftatomicity;

public interface DraftAtomicStore {

    DraftAtomicStrategy strategy();

    DraftAtomicResult saveIfNewer(
            DraftAtomicSnapshot request,
            DraftAtomicSnapshot fallback,
            long dirtyScore
    );

    boolean removeDirtyIfVersionMatches(
            Long draftId,
            long rdbContentVersion
    );
}
