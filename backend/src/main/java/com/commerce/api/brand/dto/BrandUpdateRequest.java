package com.commerce.api.brand.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 브랜드 수정 요청. 이름만 갱신한다(셀러 귀속은 별도 PUT /{id}/seller).
 */
public record BrandUpdateRequest(
        @NotBlank(message = "브랜드명은 필수입니다.")
        @Size(max = 50, message = "브랜드명은 50자 이하여야 합니다.")   // brand.name varchar(50)
        String name
) {
}
