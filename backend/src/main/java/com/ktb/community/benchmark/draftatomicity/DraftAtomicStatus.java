package com.ktb.community.benchmark.draftatomicity;

public enum DraftAtomicStatus {
    SAVED,
    IDEMPOTENT,
    VERSION_CONFLICT,
    CONTENT_CONFLICT,
    LOCK_TIMEOUT,
    WATCH_RETRY_EXHAUSTED
}
