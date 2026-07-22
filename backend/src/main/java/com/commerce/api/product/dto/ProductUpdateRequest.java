package com.commerce.api.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 상품 기본정보 수정 요청 (ADMIN). 옵션·이미지·상태는 각자 엔드포인트로 관리하므로 여기엔 없다.
 */
@Schema(description = "상품 기본정보 수정 요청")
public record ProductUpdateRequest(

        @Schema(description = "상품명(100자 이하)", example = "반팔 티셔츠")
        @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 100, message = "상품명은 100자 이하여야 합니다.")
        String name,

        @Schema(description = "판매가(원, 0 이상). 결제 기준.", example = "29000")
        @NotNull(message = "가격은 필수입니다.")
        @PositiveOrZero(message = "가격은 0 이상이어야 합니다.")
        Long price,

        @Schema(description = "정가(원, 선택). 주면 취소선+할인율 표시. 판매가 이상이어야 함. 비우면 비할인.", example = "39000")
        @PositiveOrZero(message = "정가는 0 이상이어야 합니다.")
        Long originalPrice,

        @Schema(description = "상품 설명(1000자 이하)", example = "면 100% 베이직 반팔 티셔츠")
        @Size(max = 1000, message = "설명은 1000자 이하여야 합니다.")
        String description,

        @Schema(description = "대표 이미지 URL(선택, 500자 이하)", example = "/products/3.svg")
        @Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다.")
        String imageUrl,

        @Schema(description = "카테고리 ID(선택)", example = "1")
        Long categoryId,

        @Schema(description = "브랜드 ID(선택)", example = "1")
        Long brandId
) {
}
