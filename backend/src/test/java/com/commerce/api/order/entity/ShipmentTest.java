package com.commerce.api.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shipment 엔티티 — 셀러별 배송 단위의 forward-only 전이·이력·취소 단위 테스트(#1 c안 P1).
 * 전이 규칙은 Order.advanceShipping에서 이관한 것과 동형(PAID→SHIPPING→DELIVERED). rollup/팬아웃/인가는 후속 phase.
 */
class ShipmentTest {

    private Shipment shipment(Long sellerId) {
        return Shipment.forPayment(Order.create(100L), sellerId);
    }

    @Test
    @DisplayName("결제 팬아웃 생성 - 초기 PAID + 이력 1건(null→PAID)")
    void createdAsPaid() {
        Shipment s = shipment(1L);

        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.PAID);
        assertThat(s.getSellerId()).isEqualTo(1L);
        assertThat(s.isActive()).isTrue();
        assertThat(s.getStatusHistory()).hasSize(1);
        assertThat(s.getStatusHistory().get(0).getFromStatus()).isNull();
        assertThat(s.getStatusHistory().get(0).getToStatus()).isEqualTo(ShipmentStatus.PAID);
    }

    @Test
    @DisplayName("sellerId=null - 플랫폼 직매입 버킷도 정상 생성")
    void platformBucket() {
        Shipment s = shipment(null);

        assertThat(s.getSellerId()).isNull();
        assertThat(s.belongsToSeller(null)).isTrue();     // null-safe 매칭
        assertThat(s.belongsToSeller(1L)).isFalse();
    }

    @Test
    @DisplayName("forward-only 전진 - PAID→SHIPPING→DELIVERED, 송장·이력 기록")
    void advanceForwardOnly() {
        Shipment s = shipment(1L);

        s.advanceShipping(ShipmentStatus.SHIPPING, 7L, "CJ", "1234");
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.SHIPPING);
        assertThat(s.getCourier()).isEqualTo("CJ");
        assertThat(s.getTrackingNumber()).isEqualTo("1234");
        assertThat(s.getStatusHistory()).hasSize(2);
        assertThat(s.getStatusHistory().get(1).getChangedBy()).isEqualTo(7L);   // 셀러/ADMIN 주체 기록
        assertThat(s.getStatusHistory().get(1).getMemo()).isEqualTo("CJ 1234");

        s.advanceShipping(ShipmentStatus.DELIVERED, 7L, null, null);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(s.getStatusHistory()).hasSize(3);
    }

    @Test
    @DisplayName("전이 위반 - 건너뛰기/되돌리기/CANCELLED 출발은 409")
    void illegalTransitions() {
        assertThatThrownBy(() -> shipment(1L).advanceShipping(ShipmentStatus.DELIVERED, null, null, null))
                .isInstanceOf(BusinessException.class);   // PAID→DELIVERED 건너뛰기

        Shipment shipping = shipment(1L);
        shipping.advanceShipping(ShipmentStatus.SHIPPING, null, "CJ", "1");
        assertThatThrownBy(() -> shipping.advanceShipping(ShipmentStatus.PAID, null, null, null))
                .isInstanceOf(BusinessException.class);   // SHIPPING→PAID 되돌리기
    }

    @Test
    @DisplayName("취소 - PAID에서만 가능, 멱등, SHIPPING 이후는 409")
    void cancel() {
        Shipment s = shipment(1L);
        s.cancel(9L, "셀러 항목 전량 취소");
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(s.isActive()).isFalse();
        assertThat(s.getStatusHistory()).hasSize(2);

        s.cancel(9L, "재호출");   // 멱등 no-op
        assertThat(s.getStatusHistory()).hasSize(2);

        Shipment shipped = shipment(1L);
        shipped.advanceShipping(ShipmentStatus.SHIPPING, null, "CJ", "1");
        assertThatThrownBy(() -> shipped.cancel(null, "출고 후 취소 시도"))
                .isInstanceOf(BusinessException.class);
    }
}
