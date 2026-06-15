package com.commerce.api.order.dto;

import com.commerce.api.order.entity.Order;

/**
 * 주문의 쿠폰 할인 스냅샷 — 셀러별 정산(settlement)이 할인을 분담·안분할 때 읽는 경계 DTO.
 *
 * <p>settlement → order 의존을 서비스 + DTO로만 노출한다(getOrderItems와 같은 방식, 도메인 경계 유지).
 *
 * @param discountAmount 쿠폰 할인액(원, 없으면 0)
 * @param fundedBy       부담 주체("PLATFORM"/"SELLER", 없으면 null) — 정산 net 분담에 사용
 * @param sellerId       셀러 한정 쿠폰이면 그 셀러 ID(플랫폼 와이드면 null) — 할인 귀속에 사용
 */
public record OrderDiscountInfo(
        long discountAmount,
        String fundedBy,
        Long sellerId
) {
    /** 할인 없음(쿠폰 미적용 주문). */
    public static OrderDiscountInfo none() {
        return new OrderDiscountInfo(0L, null, null);
    }

    public static OrderDiscountInfo from(Order order) {
        return new OrderDiscountInfo(
                order.getDiscountAmount(), order.getCouponFundedBy(), order.getCouponSellerId());
    }

    /** 적용된 할인이 있는지. */
    public boolean hasDiscount() {
        return discountAmount > 0;
    }
}
