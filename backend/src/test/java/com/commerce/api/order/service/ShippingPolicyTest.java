package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배송비 정책(#4) 단위 테스트 — 정액 + 무료임계 경계. 기준액 = 할인 후 상품금액.
 */
class ShippingPolicyTest {

    private final ShippingPolicy policy = new ShippingPolicy(3000L, 50000L);

    @Test
    @DisplayName("임계 미만 - 정액 배송비 부과")
    void belowThreshold_flatFee() {
        assertThat(policy.feeFor(49999L)).isEqualTo(3000L);
        assertThat(policy.feeFor(0L)).isEqualTo(3000L);
    }

    @Test
    @DisplayName("임계 이상(경계 포함) - 무료(0)")
    void atOrAboveThreshold_free() {
        assertThat(policy.feeFor(50000L)).isZero();   // 경계는 무료(>=)
        assertThat(policy.feeFor(80000L)).isZero();
    }
}
