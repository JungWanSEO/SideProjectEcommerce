package com.commerce.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 체크아웃 요청.
 * 주문 항목은 서버 장바구니에서 가져오므로(위변조 방지) 여기엔 없다 — 배송지(주소록 id)와 배송 메모만 받는다.
 */
@Schema(description = "체크아웃 요청 (주소록에서 배송지 선택)")
public record CheckoutRequest(

        @Schema(description = "배송지로 쓸 주소록 항목 ID(본인 소유)", example = "1")
        @NotNull(message = "배송지를 선택해 주세요.")
        Long addressId,

        @Schema(description = "배송 요청사항(선택)", example = "부재 시 경비실에 맡겨주세요.")
        @Size(max = 200, message = "배송 메모는 200자 이내여야 합니다.")
        String deliveryMemo,

        @Schema(description = "적용할 쿠폰 코드(선택). 비우면 할인 없음.", example = "WELCOME5000")
        @Size(max = 40, message = "쿠폰 코드는 40자 이내여야 합니다.")
        String couponCode
) {
}
