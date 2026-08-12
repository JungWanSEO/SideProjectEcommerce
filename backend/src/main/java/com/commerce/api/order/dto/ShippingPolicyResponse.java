package com.commerce.api.order.dto;

/**
 * 배송비 정책 응답(#4) — FE가 장바구니·체크아웃에서 배송비·무료배송 진행바를 그릴 때 읽는다.
 * (쿠폰 없이도 배송비를 표시해야 하므로 쿠폰 프리뷰와 별개로 정책값 자체를 노출한다.)
 *
 * @param flatFee       정액 배송비(원)
 * @param freeThreshold 무료배송 임계액(원, 할인 후 상품금액 기준). 이 값 이상이면 배송비 0.
 * @param returnFee     반품 회수비(원, #8 후속). 고객 귀책 반품에서 환불액에서 차감될 수 있는 금액.
 *                      반품 신청 화면의 <b>사전 고지</b>에 쓴다 — 모르고 차감당하는 게 가장 흔한 CS 발화점이다.
 *                      무료배송 주문에도 동일 부과한다(실제 회수 물류비는 원배송이 무료였다고 0이 아니다).
 */
public record ShippingPolicyResponse(
        long flatFee,
        long freeThreshold,
        long returnFee
) {
}
