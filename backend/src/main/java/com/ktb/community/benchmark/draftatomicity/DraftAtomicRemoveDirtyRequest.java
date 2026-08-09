package com.ktb.community.benchmark.draftatomicity;

import jakarta.validation.constraints.Min;

public record DraftAtomicRemoveDirtyRequest(
        @Min(0) long rdbContentVersion
) {
}
