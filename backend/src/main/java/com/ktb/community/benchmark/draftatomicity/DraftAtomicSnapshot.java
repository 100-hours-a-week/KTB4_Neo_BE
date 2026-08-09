package com.ktb.community.benchmark.draftatomicity;

import java.time.LocalDateTime;

public record DraftAtomicSnapshot(
        Long draftId,
        String title,
        String postBody,
        String postImage,
        long contentVersion,
        LocalDateTime updatedAt
) {
}
