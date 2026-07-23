package com.commerce.api.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Order 엔티티 — 쿠폰 할인의 항목별 안분(discountShares) 단위 테스트 (쿠폰 Step 2b).
 * 이 안분값(항목 실효가 = 소계 − share)이 부분환불 환불액·셀러별 정산의 단일 출처다.
 */
class OrderTest {

    private OrderItem item(Long sellerId, long subtotal) {
        return OrderItem.builder()
                .productId(1L).optionId(1L).sellerId(sellerId)
                .productName("P").size("M").orderPrice(subtotal).quantity(1).build();
    }

    private Order orderWith(OrderItem... items) {
        Order order = Order.create(100L);
        for (OrderItem it : items) {
            order.addItem(it);
        }
        return order;
    }

    @Test
    @DisplayName("플랫폼 와이드 - 매출 비례로 항목 안분")
    void platformWideProportional() {
        OrderItem a = item(1L, 6000L);
        OrderItem b = item(2L, 4000L);
        Order order = orderWith(a, b);
        order.applyCoupon("C", 1000L, "PLATFORM", null);

        Map<OrderItem, Long> shares = order.discountShares();
        assertThat(shares.get(a)).isEqualTo(600L);   // 1000 × 6000/10000
        assertThat(shares.get(b)).isEqualTo(400L);   // 1000 × 4000/10000
    }

    @Test
    @DisplayName("셀러 한정 - 그 셀러 항목에만 안분, 다른 셀러는 0")
    void sellerScopedOnlyThatSeller() {
        OrderItem a = item(1L, 6000L);
        OrderItem b = item(2L, 4000L);
        Order order = orderWith(a, b);
        order.applyCoupon("C", 1000L, "SELLER", 1L);   // 셀러1 한정

        Map<OrderItem, Long> shares = order.discountShares();
        assertThat(shares.get(a)).isEqualTo(1000L);   // 셀러1 항목 전액(범위 내 유일)
        assertThat(shares.get(b)).isZero();           // 셀러2 항목은 범위 밖
    }

    @Test
    @DisplayName("반올림 잔차는 매출 최대 항목에 — Σshare = 할인액")
    void residualToLargestItem() {
        OrderItem a = item(1L, 3333L);
        OrderItem b = item(1L, 3333L);
        OrderItem c = item(1L, 3334L);
        Order order = orderWith(a, b, c);
        order.applyCoupon("C", 1000L, "PLATFORM", null);

        Map<OrderItem, Long> shares = order.discountShares();
        long sum = shares.get(a) + shares.get(b) + shares.get(c);
        assertThat(sum).isEqualTo(1000L);             // 잔차 보정으로 합 보존
        assertThat(shares.get(c)).isEqualTo(334L);    // 333 + 잔차 1 (최대 매출 항목)
    }

    @Test
    @DisplayName("쿠폰 없는 주문 - 모든 항목 share 0")
    void noDiscountAllZero() {
        OrderItem a = item(1L, 6000L);
        OrderItem b = item(2L, 4000L);
        Order order = orderWith(a, b);

        Map<OrderItem, Long> shares = order.discountShares();
        assertThat(shares.get(a)).isZero();
        assertThat(shares.get(b)).isZero();
    }

    // === #1 P2: 결제 팬아웃 · 백필 =====================================================

    @Test
    @DisplayName("markPaid - 활성 항목을 셀러별로 팬아웃해 shipment 생성(플랫폼 null 버킷 포함, 모두 PAID)")
    void markPaid_fansOutShipmentsBySeller() {
        Order order = orderWith(item(1L, 5000L), item(1L, 3000L), item(2L, 4000L), item(null, 2000L));

        order.markPaid();

        // 셀러1(항목 2개→1건), 셀러2, 플랫폼(null) → 셀러당 shipment 1건 = 3건
        assertThat(order.getShipments()).hasSize(3);
        assertThat(order.getShipments()).extracting(Shipment::getSellerId)
                .containsExactlyInAnyOrder(1L, 2L, null);
        assertThat(order.getShipments()).allMatch(s -> s.getStatus() == ShipmentStatus.PAID);
    }

    @Test
    @DisplayName("markPaid - 전량 취소된 셀러는 shipment 없음(활성 항목 기준, 정산과 정합)")
    void markPaid_skipsFullyCancelledSeller() {
        OrderItem s1 = item(1L, 5000L);
        OrderItem s2 = item(2L, 4000L);
        Order order = orderWith(s1, s2);
        s2.cancel();   // 셀러2 항목 PENDING 중 취소

        order.markPaid();

        assertThat(order.getShipments()).extracting(Shipment::getSellerId).containsExactly(1L);
    }

    @Test
    @DisplayName("backfillShipments - 현재 상태 상속 + 송장 복제, per-order 멱등")
    void backfill_inheritsStatusAndIdempotent() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1234");
        order.getShipments().clear();   // 레거시(P2 이전) 시뮬 — shipment 없음

        assertThat(order.backfillShipments()).isTrue();
        assertThat(order.getShipments()).hasSize(2);
        assertThat(order.getShipments()).allMatch(s -> s.getStatus() == ShipmentStatus.SHIPPING); // 상태 상속
        assertThat(order.getShipments()).allMatch(s -> "CJ".equals(s.getCourier()));              // 송장 복제

        assertThat(order.backfillShipments()).isFalse();   // 이미 있음 → 멱등 no-op
        assertThat(order.getShipments()).hasSize(2);
    }

    @Test
    @DisplayName("backfillShipments - CANCELLED 주문은 생략(출고 무의미)")
    void backfill_skipsCancelled() {
        Order order = orderWith(item(1L, 5000L));
        order.markPaid();
        order.getShipments().clear();
        order.cancel();   // PAID → CANCELLED

        assertThat(order.backfillShipments()).isFalse();
        assertThat(order.getShipments()).isEmpty();
    }
}
