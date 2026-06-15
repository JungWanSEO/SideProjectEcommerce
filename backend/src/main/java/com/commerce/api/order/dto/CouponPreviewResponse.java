package com.commerce.api.order.dto;

/**
 * 쿠폰 미리보기 응답 — 현재 장바구니에 쿠폰을 적용했을 때의 할인·결제 예정액.
 *
 * <p>주문을 만들지 않고 계산만 한 결과다. 적용 불가(코드 없음·기간 외·최소금액 미달 등)면
 * 미리보기 호출이 400으로 사유를 돌려준다(체크아웃과 같은 검증).
 *
 * @param couponCode     적용된(정규화된) 쿠폰 코드
 * @param totalPrice     할인 전 장바구니 합계(원)
 * @param discountAmount 할인액(원)
 * @param payableAmount  예상 결제액(= totalPrice - discountAmount)
 */
public record CouponPreviewResponse(
        String couponCode,
        long totalPrice,
        long discountAmount,
        long payableAmount
) {
}
