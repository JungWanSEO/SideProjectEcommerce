package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.common.CancelReason.Fault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배송비 정책(#4) 단위 테스트 — 정액 + 무료임계 경계. 기준액 = 할인 후 상품금액.
 * 반품 회수비 부담 매트릭스(#8 후속)도 여기서 못 박는다.
 */
class ShippingPolicyTest {

    private final ShippingPolicy policy = new ShippingPolicy(3000L, 50000L, 3000L);

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

    @Test
    @DisplayName("회수비 부담 매트릭스 - 고객 귀책만 고객이, 셀러 귀책만 셀러가 문다")
    void returnChargeMatrix() {
        // 고객 귀책 → 고객이 물고 셀러는 0
        assertThat(policy.customerChargeOf(3000L, Fault.CUSTOMER)).isEqualTo(3000L);
        assertThat(policy.sellerChargeOf(3000L, Fault.CUSTOMER)).isZero();
        // 셀러 귀책 → 셀러가 물고 고객은 0
        assertThat(policy.customerChargeOf(3000L, Fault.SELLER)).isZero();
        assertThat(policy.sellerChargeOf(3000L, Fault.SELLER)).isEqualTo(3000L);
        // 플랫폼 귀책·미상 → 아무도 안 문다(플랫폼 흡수)
        for (Fault absorbed : new Fault[]{Fault.PLATFORM, Fault.NONE}) {
            assertThat(policy.customerChargeOf(3000L, absorbed)).isZero();
            assertThat(policy.sellerChargeOf(3000L, absorbed)).isZero();
        }
    }

    @Test
    @DisplayName("회수비는 스냅샷 요율로 계산한다 - 정책값이 올라도 진행 중 반품은 불변")
    void returnChargeUsesSnapshotRate() {
        // 정책은 3000이지만 신청 시점 스냅샷이 2000이면 2000이 부과된다
        assertThat(policy.customerChargeOf(2000L, Fault.CUSTOMER)).isEqualTo(2000L);
        // 레거시(스냅샷 0)는 소급 부과하지 않는다
        assertThat(policy.customerChargeOf(0L, Fault.CUSTOMER)).isZero();
    }
}
