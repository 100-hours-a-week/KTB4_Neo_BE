package com.ktb.community.benchmark.draftatomicity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record DraftAtomicInitializeRequest(
        @NotNull Long draftId,
        String title,
        String postBody,
        String postImage,
        @NotNull @Min(1) Long contentVersion,
        @NotNull LocalDateTime updatedAt,
        boolean dirty
) {
    DraftAtomicSnapshot snapshot() {
        return new DraftAtomicSnapshot(
                draftId,
                title,
                postBody,
                postImage,
                contentVersion,
                updatedAt
        );
    }
}
