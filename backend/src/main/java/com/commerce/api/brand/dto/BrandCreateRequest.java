package com.commerce.api.brand.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 브랜드 등록 요청.
 */
public record BrandCreateRequest(
        @NotBlank(message = "브랜드명은 필수입니다.")
        @Size(max = 50, message = "브랜드명은 50자 이하여야 합니다.")   // brand.name varchar(50) — 초과 시 DB 500 대신 400
        String name
) {
}
