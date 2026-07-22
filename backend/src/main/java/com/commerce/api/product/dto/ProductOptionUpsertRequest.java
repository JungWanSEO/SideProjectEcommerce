package com.commerce.api.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 상품 옵션(사이즈) 추가·수정 요청. 사이즈 + 재고(절대값).
 *
 * <p>상품 등록 시 옵션({@link ProductCreateRequest.ProductOptionRequest})과 같은 모양이지만,
 * 옵션 단건 추가/수정 엔드포인트 전용으로 분리해 상품 등록 DTO와 결합하지 않는다.
 */
@Schema(description = "상품 옵션 추가/수정 요청")
public record ProductOptionUpsertRequest(

        @Schema(description = "사이즈", example = "M")
        @NotBlank(message = "사이즈는 필수입니다.")
        @Size(max = 30, message = "사이즈는 30자 이하여야 합니다.")   // product_option.size varchar(30)
        String size,

        @Schema(description = "재고 수량(0 이상)", example = "100")
        @NotNull(message = "재고는 필수입니다.")
        @PositiveOrZero(message = "재고는 0 이상이어야 합니다.")
        Integer stock
) {
}
