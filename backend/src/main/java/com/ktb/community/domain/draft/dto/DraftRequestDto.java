package com.ktb.community.domain.draft.dto;

import static com.ktb.community.domain.draft.support.DraftContentNormalizer.isEmpty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DraftRequestDto {

    @Size(max = 255)
    private String title;

    private String postBody;

    @Size(max = 500)
    private String postImage;

    @NotNull
    @Min(1)
    private Long contentVersion;

    public boolean isEmptyContent() {
        return isEmpty(
                title,
                postBody,
                postImage
        );
    }
}
