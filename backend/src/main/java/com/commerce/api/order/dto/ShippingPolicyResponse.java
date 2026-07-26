package com.commerce.api.order.dto;

/**
 * 배송비 정책 응답(#4) — FE가 장바구니·체크아웃에서 배송비·무료배송 진행바를 그릴 때 읽는다.
 * (쿠폰 없이도 배송비를 표시해야 하므로 쿠폰 프리뷰와 별개로 정책값 자체를 노출한다.)
 *
 * @param flatFee       정액 배송비(원)
 * @param freeThreshold 무료배송 임계액(원, 할인 후 상품금액 기준). 이 값 이상이면 배송비 0.
 */
public record ShippingPolicyResponse(
        long flatFee,
        long freeThreshold
) {
}
