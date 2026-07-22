package com.commerce.api.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카테고리 수정 요청. 이름과 부모를 함께 갱신한다.
 * parentId=null이면 최상위로, 값이면 그 카테고리의 자식(2단계)으로 옮긴다(검증은 서비스).
 */
public record CategoryUpdateRequest(
        @NotBlank(message = "카테고리명은 필수입니다.")
        @Size(max = 50, message = "카테고리명은 50자 이하여야 합니다.")   // category.name varchar(50)
        String name,

        @Schema(description = "부모 카테고리 ID(선택). null이면 최상위, 값이면 그 카테고리의 자식으로(2단계까지).", example = "1")
        Long parentId
) {
    /** parentId 없는 호출용 편의 생성자(최상위). */
    public CategoryUpdateRequest(String name) {
        this(name, null);
    }
}
