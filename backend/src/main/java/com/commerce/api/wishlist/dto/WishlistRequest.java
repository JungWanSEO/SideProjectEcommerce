package com.commerce.api.wishlist.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 찜 추가 요청 바디. 어떤 상품을 찜할지 productId만 받는다(회원은 인증 토큰에서 얻음 — 바디로 안 받는다).
 */
public record WishlistRequest(
        @NotNull(message = "상품 ID는 필수입니다.")
        Long productId
) {
}
