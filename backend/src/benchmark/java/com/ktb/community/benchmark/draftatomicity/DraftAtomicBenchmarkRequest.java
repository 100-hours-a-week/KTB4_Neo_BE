package com.ktb.community.benchmark.draftatomicity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record DraftAtomicBenchmarkRequest(
        String title,
        String postBody,
        String postImage,
        @NotNull @Min(1) Long contentVersion,
        @NotNull LocalDateTime updatedAt,
        String fallbackTitle,
        String fallbackPostBody,
        String fallbackPostImage,
        @NotNull @Min(1) Long fallbackContentVersion,
        @NotNull LocalDateTime fallbackUpdatedAt,
        Long dirtyScore
) {
    DraftAtomicSnapshot requestSnapshot(Long draftId) {
        return new DraftAtomicSnapshot(
                draftId,
                title,
                postBody,
                postImage,
                contentVersion,
                updatedAt
        );
    }

    DraftAtomicSnapshot fallbackSnapshot(Long draftId) {
        return new DraftAtomicSnapshot(
                draftId,
                fallbackTitle,
                fallbackPostBody,
                fallbackPostImage,
                fallbackContentVersion,
                fallbackUpdatedAt
        );
    }
}
