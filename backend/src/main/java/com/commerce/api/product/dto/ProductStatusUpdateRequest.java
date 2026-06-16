package com.commerce.api.product.dto;

import com.commerce.api.product.entity.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 상품 상태 변경 요청 (ADMIN). ON_SALE / SOLD_OUT / DISCONTINUED.
 */
@Schema(description = "상품 상태 변경 요청")
public record ProductStatusUpdateRequest(

        @Schema(description = "변경할 상태", example = "DISCONTINUED")
        @NotNull(message = "상태는 필수입니다.")
        ProductStatus status
) {
}
