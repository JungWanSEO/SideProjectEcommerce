package com.commerce.api.brand.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 브랜드 수정 요청. 이름만 갱신한다(셀러 귀속은 별도 PUT /{id}/seller).
 */
public record BrandUpdateRequest(
        @NotBlank(message = "브랜드명은 필수입니다.") String name
) {
}
