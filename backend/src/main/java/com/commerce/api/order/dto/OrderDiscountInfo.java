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
 * @param shippingFee    배송비 스냅샷(원, #4). 정산이 플랫폼 배송비 엔트리를 만들 때 읽는다(셀러 net엔 미포함).
 * @param returnShippingCharge 고객 부담 반품 회수비 누계(원, #8 후속). 플랫폼 회수비 수익 엔트리의 target.
 */
public record OrderDiscountInfo(
        long discountAmount,
        String fundedBy,
        Long sellerId,
        long shippingFee,
        long returnShippingCharge
) {
    /**
     * 회수비 없는 편의 생성자 — 기존 호출부(테스트 mock 8곳) 무변경.
     * 회수비 0이면 정산이 회수비 엔트리를 만들지 않으므로 기존 기대값과 동일하다.
     */
    public OrderDiscountInfo(long discountAmount, String fundedBy, Long sellerId, long shippingFee) {
        this(discountAmount, fundedBy, sellerId, shippingFee, 0L);
    }

    /** 할인 없음(쿠폰 미적용 주문). */
    public static OrderDiscountInfo none() {
        return new OrderDiscountInfo(0L, null, null, 0L, 0L);
    }

    public static OrderDiscountInfo from(Order order) {
        return new OrderDiscountInfo(
                order.getDiscountAmount(), order.getCouponFundedBy(), order.getCouponSellerId(),
                order.getShippingFee(), order.getReturnShippingCharge());
    }

    /** 적용된 할인이 있는지. */
    public boolean hasDiscount() {
        return discountAmount > 0;
    }
}
