package com.commerce.api.activity.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 상품 조회 기록 요청 바디. 어떤 상품을 봤는지 productId만 받는다(회원은 인증 토큰에서).
 */
public record ActivityViewRequest(
        @NotNull(message = "상품 ID는 필수입니다.")
        Long productId
) {
}
