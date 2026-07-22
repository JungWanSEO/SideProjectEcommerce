package com.commerce.api.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카테고리 등록 요청. parentId를 주면 그 카테고리의 자식(2단계)으로 등록한다(null=최상위).
 */
public record CategoryCreateRequest(
        @NotBlank(message = "카테고리명은 필수입니다.")
        @Size(max = 50, message = "카테고리명은 50자 이하여야 합니다.")   // category.name varchar(50)
        String name,

        @Schema(description = "부모 카테고리 ID(선택). 주면 그 카테고리의 자식으로 등록(2단계까지).", example = "1")
        Long parentId
) {
    /** parentId 없는 호출용 편의 생성자(최상위). 기존 호출부 호환. */
    public CategoryCreateRequest(String name) {
        this(name, null);
    }
}
