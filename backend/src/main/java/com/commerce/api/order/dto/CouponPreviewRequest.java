package com.commerce.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 쿠폰 미리보기 요청 — 체크아웃 전에 "내 장바구니에 이 코드를 쓰면 얼마 할인?"을 묻는다.
 * 주문을 만들지 않고 현재 서버 장바구니 기준으로 할인액만 계산해 돌려준다.
 */
@Schema(description = "쿠폰 미리보기 요청")
public record CouponPreviewRequest(

        @Schema(description = "미리볼 쿠폰 코드", example = "WELCOME5000")
        @NotBlank(message = "쿠폰 코드를 입력해 주세요.")
        @Size(max = 40, message = "쿠폰 코드는 40자 이내여야 합니다.")
        String couponCode
) {
}
