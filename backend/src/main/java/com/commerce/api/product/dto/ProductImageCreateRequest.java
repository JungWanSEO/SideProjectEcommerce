package com.commerce.api.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 상품 이미지(갤러리) 추가 요청 (ADMIN). 이미지 URL 1건.
 */
@Schema(description = "상품 이미지 추가 요청")
public record ProductImageCreateRequest(

        @Schema(description = "이미지 URL(500자 이하)", example = "/products/2.svg")
        @NotBlank(message = "이미지 URL은 필수입니다.")
        @Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다.")
        String url
) {
}
