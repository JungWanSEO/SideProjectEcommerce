package com.commerce.api.order.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 배송비 정책(#4) — <b>정액 + 무료배송 임계</b>(오너 결정). 주문 단위 단일 배송비이며 셀러별이 아니다.
 *
 * <p>값은 기존 컨벤션대로 {@code @Value}로 주입한다(@ConfigurationProperties 미도입 — app.* 는 전부 @Value).
 * 무료배송 임계 판정 기준액은 <b>할인 후 상품금액</b>(소계 − 쿠폰할인, 배송비 제외)이다 — 쿠폰으로 실제 상품
 * 결제액이 임계 아래로 내려가면 배송비가 붙는다(고객이 "상품에 실제로 낸 금액" 기준).
 *
 * <p>계산 결과는 {@code Order.assignShippingFee}로 <b>주문 생성 시점에 스냅샷</b>되므로, 이후 정책값이 바뀌어도
 * 과거 주문의 결제액/환불/정산은 불변이다. 배송비는 플랫폼 수익이라 셀러 정산 net에는 포함되지 않는다.
 */
@Getter
@Component
public class ShippingPolicy {

    /** 정액 배송비(원). 무료임계 미달 시 부과. */
    private final long flatFee;
    /** 무료배송 임계액(원). 할인 후 상품금액이 이 값 이상이면 배송비 0. */
    private final long freeThreshold;

    public ShippingPolicy(
            @Value("${app.order.shipping-fee:3000}") long flatFee,
            @Value("${app.order.free-shipping-threshold:50000}") long freeThreshold) {
        this.flatFee = flatFee;
        this.freeThreshold = freeThreshold;
    }

    /**
     * 배송비 계산 — 할인 후 상품금액(소계 − 할인)이 무료임계 이상이면 0, 아니면 정액.
     *
     * @param amountAfterDiscount 할인 후 상품금액(배송비 제외). 임계 판정 기준.
     */
    public long feeFor(long amountAfterDiscount) {
        return amountAfterDiscount >= freeThreshold ? 0L : flatFee;
    }
}
