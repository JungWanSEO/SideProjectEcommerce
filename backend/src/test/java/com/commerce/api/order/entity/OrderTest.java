package com.commerce.api.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.common.CancelReason;
import com.commerce.api.global.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    // === #4 배송비: payable 접기(활성 항목 있을 때만 가산) ============================

    @Test
    @DisplayName("배송비 - 활성 항목이 있으면 payable = 소계 + 배송비")
    void payable_includesShippingWhenActive() {
        Order order = orderWith(item(1L, 10000L));
        order.assignShippingFee(3000L);
        assertThat(order.getPayableAmount()).isEqualTo(13000L);
    }

    @Test
    @DisplayName("배송비 - 할인과 함께: payable = 소계 − 할인 + 배송비")
    void payable_withDiscountAndShipping() {
        Order order = orderWith(item(1L, 10000L));
        order.applyCoupon("C", 1000L, "PLATFORM", null);
        order.assignShippingFee(3000L);
        assertThat(order.getPayableAmount()).isEqualTo(12000L);   // 10000 − 1000 + 3000
    }

    @Test
    @DisplayName("배송비 - 전량취소면 payable 0(배송비까지 빠져 전액 환불로 이어짐)")
    void payable_dropsShippingWhenAllCancelled() {
        OrderItem only = item(1L, 10000L);
        Order order = orderWith(only);
        order.assignShippingFee(3000L);
        only.cancel();   // 활성 0
        assertThat(order.getPayableAmount()).isZero();
    }

    @Test
    @DisplayName("배송비 - 부분취소면 배송비 유지(남은 활성 실효가 + 배송비)")
    void payable_retainsShippingOnPartialCancel() {
        OrderItem a = item(1L, 10000L);
        OrderItem b = item(2L, 20000L);
        Order order = orderWith(a, b);
        order.assignShippingFee(3000L);
        a.cancel();   // b만 활성
        assertThat(order.getPayableAmount()).isEqualTo(23000L);   // 20000 + 배송비 3000 유지
    }

    @Test
    @DisplayName("배송비 - 반품(RETURNED)도 남은 활성엔 배송비 유지")
    void payable_retainsShippingOnReturn() {
        OrderItem a = item(1L, 10000L);
        OrderItem b = item(2L, 20000L);
        Order order = orderWith(a, b);
        order.assignShippingFee(3000L);
        a.markReturned();   // b만 활성
        assertThat(order.getPayableAmount()).isEqualTo(23000L);
    }

    @Test
    @DisplayName("배송비 - 전량반품(RETURNED)이면 배송비 유지(payable=배송비), 전량취소와 구분(적대적리뷰 HIGH)")
    void payable_retainsShippingWhenAllReturned() {
        OrderItem only = item(1L, 10000L);
        Order order = orderWith(only);
        order.assignShippingFee(3000L);
        only.markReturned();   // 활성 0이지만 RETURNED → 배송 이미 소비 → 배송비 유지
        assertThat(order.getPayableAmount()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("배송비 - 반품 후 마지막 항목 취소해도 배송비 유지(RETURNED 있으면 전량취소 아님·순서 무관)")
    void payable_returnThenCancelLast_retainsShipping() {
        OrderItem a = item(1L, 20000L);
        OrderItem b = item(2L, 20000L);
        Order order = orderWith(a, b);
        order.assignShippingFee(3000L);
        a.markReturned();   // A 반품(배송비 유지)
        b.cancel();         // B 취소 → 활성 0, 그러나 A=RETURNED라 전량취소 아님
        assertThat(order.getPayableAmount()).isEqualTo(3000L);   // 배송비 유지(전량취소만 환불)
    }

    @Test
    @DisplayName("배송비 - 음수는 400")
    void assignShippingFee_negativeRejected() {
        Order order = orderWith(item(1L, 10000L));
        assertThatThrownBy(() -> order.assignShippingFee(-1L))
                .isInstanceOf(BusinessException.class);
    }

    // === #8 취소 사유 ============================================================

    @Test
    @DisplayName("취소 사유 - 주문 취소 시 각 항목에 사유 기록(기록·집계 전용)")
    void cancel_recordsReason() {
        OrderItem a = item(1L, 10000L);
        OrderItem b = item(2L, 20000L);
        Order order = orderWith(a, b);   // PENDING(출고 전) — 전량 취소 가능

        order.cancel(100L, "주문자 취소", CancelReason.CHANGE_OF_MIND);

        assertThat(a.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
        assertThat(a.getCancelReason()).isEqualTo(CancelReason.CHANGE_OF_MIND);
        assertThat(b.getCancelReason()).isEqualTo(CancelReason.CHANGE_OF_MIND);
    }

    @Test
    @DisplayName("취소 사유 - OrderItem.cancel(reason)이 사유 기록, 사유 없는 취소는 null")
    void itemCancel_recordsReason() {
        OrderItem a = item(1L, 10000L);
        a.cancel(CancelReason.DEFECTIVE);
        assertThat(a.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
        assertThat(a.getCancelReason()).isEqualTo(CancelReason.DEFECTIVE);

        OrderItem b = item(2L, 20000L);
        b.cancel();   // 사유 미상(시스템)
        assertThat(b.getCancelReason()).isNull();
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
        order.advanceShipping(OrderStatus.SHIPPING);   // 멀티셀러라 상태-only 일괄 전진(송장은 per-shipment 경로)
        order.getShipments().clear();   // 레거시(P2 이전) 시뮬 — shipment 없음

        assertThat(order.backfillShipments()).isTrue();
        assertThat(order.getShipments()).hasSize(2);
        assertThat(order.getShipments()).allMatch(s -> s.getStatus() == ShipmentStatus.SHIPPING); // 상태 상속
        // 레거시 주문은 셀러별 개별 송장 정보가 없으므로 백필은 courier/tracking을 비운다(orders 단일 송장 컬럼은 P6 DROP).
        assertThat(order.getShipments()).allMatch(s -> s.getCourier() == null);

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

    // === #1 P3: Order.status rollup(shipment 파생) ====================================

    @Test
    @DisplayName("rollup - 하나라도 출고 시작이면 주문 SHIPPING")
    void rollup_anyShippingMakesOrderShipping() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();   // shipment 2건 PAID → 주문 PAID
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        order.getShipments().get(0).advanceShipping(ShipmentStatus.SHIPPING, null, "CJ", "1");
        order.recomputeStatusFromShipments(null, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);   // 셀러A만 출고해도 주문 SHIPPING
    }

    @Test
    @DisplayName("rollup - 전부 DELIVERED만 DELIVERED, 일부만 완료면 SHIPPING(forward-only 단조)")
    void rollup_allDeliveredOnly() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();
        Shipment a = order.getShipments().get(0);
        Shipment b = order.getShipments().get(1);
        a.advanceShipping(ShipmentStatus.SHIPPING, null, "CJ", "1");
        b.advanceShipping(ShipmentStatus.SHIPPING, null, "CJ", "2");

        a.advanceShipping(ShipmentStatus.DELIVERED, null, null, null);
        order.recomputeStatusFromShipments(null, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);   // A완료·B배송중 → 아직 SHIPPING(후퇴 없음)

        b.advanceShipping(ShipmentStatus.DELIVERED, null, null, null);
        order.recomputeStatusFromShipments(null, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);  // 둘 다 완료
    }

    @Test
    @DisplayName("rollup - 일부 취소는 남은 활성 기준, 전부 취소면 CANCELLED")
    void rollup_cancellations() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();
        Shipment a = order.getShipments().get(0);
        Shipment b = order.getShipments().get(1);

        a.cancel(null, "셀러A 취소");
        order.recomputeStatusFromShipments(null, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);       // B 활성 PAID

        b.cancel(null, "셀러B 취소");
        order.recomputeStatusFromShipments(null, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);  // 전부 취소
    }

    @Test
    @DisplayName("rollup - 값이 바뀔 때만 주문 이력 append(불변이면 shipment 이력만)")
    void rollup_recordsHistoryOnChangeOnly() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();   // 주문 이력: PENDING, PAID
        int base = order.getStatusHistory().size();

        order.getShipments().get(0).advanceShipping(ShipmentStatus.SHIPPING, 7L, "CJ", "1");
        order.recomputeStatusFromShipments(7L, "CJ 1");
        assertThat(order.getStatusHistory()).hasSize(base + 1);   // PAID→SHIPPING 변화 → 이력 1건

        order.getShipments().get(1).advanceShipping(ShipmentStatus.SHIPPING, 8L, "CJ", "2");
        order.recomputeStatusFromShipments(8L, "CJ 2");
        assertThat(order.getStatusHistory()).hasSize(base + 1);   // rollup 여전히 SHIPPING → 주문 이력 불변
    }

    // === #1 회귀 교정: 멀티셀러 일괄 전진 운송장 가드 (적대적 스캔 확정②) ==============

    @Test
    @DisplayName("일괄 전진 - 멀티셀러 주문에 송장 실으면 400(운송장 복제 방지·per-shipment 유도)")
    void advanceShipping_multiSellerWithTracking_rejected() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();   // 셀러 2건 shipment(PAID)

        assertThatThrownBy(() -> order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "111"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("운송장");
        // 거부됐으니 어떤 shipment에도 송장이 붙거나 상태가 전진하지 않았다(원자적 롤백 아님·엔티티 선검증)
        assertThat(order.getShipments()).allMatch(s -> s.getTrackingNumber() == null);
        assertThat(order.getShipments()).allMatch(s -> s.getStatus() == ShipmentStatus.PAID);
    }

    @Test
    @DisplayName("일괄 전진 - 멀티셀러라도 송장 없이 상태만 전진은 허용(모두 SHIPPING)")
    void advanceShipping_multiSellerStatusOnly_allowed() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();

        order.advanceShipping(OrderStatus.SHIPPING);   // 송장 없이 일괄

        assertThat(order.getShipments()).allMatch(s -> s.getStatus() == ShipmentStatus.SHIPPING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("일괄 전진 - 단일 셀러는 송장 동반 허용(전진 대상 1건)")
    void advanceShipping_singleSellerWithTracking_allowed() {
        Order order = orderWith(item(1L, 5000L), item(1L, 3000L));   // 같은 셀러 → shipment 1건
        order.markPaid();

        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "111");

        assertThat(order.getShipments()).hasSize(1);
        assertThat(order.getShipments().get(0).getTrackingNumber()).isEqualTo("111");
        assertThat(order.getShipments().get(0).getStatus()).isEqualTo(ShipmentStatus.SHIPPING);
    }

    // === #1 P4: shipment-grain 취소 ===================================================

    private Shipment shipmentOfSeller(Order order, Long sellerId) {
        return order.getShipments().stream().filter(s -> s.belongsToSeller(sellerId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("cancel(부분) - 출고된 셀러 항목은 남고 미출고 셀러만 취소, 주문은 SHIPPING 유지")
    void cancel_partial_keepsShippedSeller() {
        OrderItem s1 = item(1L, 5000L);
        OrderItem s2 = item(2L, 4000L);
        Order order = orderWith(s1, s2);
        order.markPaid();
        shipmentOfSeller(order, 1L).advanceShipping(ShipmentStatus.SHIPPING, null, "CJ", "1");
        order.recomputeStatusFromShipments(null, null);   // 셀러1 출고 → 주문 SHIPPING

        List<OrderItem> cancelled = order.cancel(100L, "부분취소");

        assertThat(cancelled).containsExactly(s2);           // 미출고 셀러2 항목만 취소
        assertThat(s1.isActive()).isTrue();                  // 출고된 셀러1 항목은 남음
        assertThat(s2.isActive()).isFalse();
        assertThat(shipmentOfSeller(order, 2L).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);  // 셀러2 shipment 취소
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);   // 셀러1 여전히 배송중
    }

    @Test
    @DisplayName("cancel(전부 출고) - 모든 셀러가 출고 시작이면 409")
    void cancel_allShipped_conflict() {
        Order order = orderWith(item(1L, 5000L), item(2L, 4000L));
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING);   // 두 shipment 모두 SHIPPING

        assertThatThrownBy(() -> order.cancel(100L, "취소시도"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("배송이 시작");
    }

    @Test
    @DisplayName("cancelItem - 출고 시작된 셀러 항목은 409")
    void cancelItem_shipped_blocked() {
        OrderItem s1 = item(1L, 5000L);
        Order order = orderWith(s1, item(2L, 4000L));
        order.markPaid();
        ReflectionTestUtils.setField(s1, "id", 500L);
        shipmentOfSeller(order, 1L).advanceShipping(ShipmentStatus.SHIPPING, null, "CJ", "1");

        assertThatThrownBy(() -> order.cancelItem(500L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("배송이 시작");
    }

    @Test
    @DisplayName("cancelItem - 결제 전(PENDING) 주문은 항목별 취소 자체가 409(할인 몫 소멸 → 과다청구 차단)")
    void cancelItem_pending_blocked() {
        OrderItem s1 = item(1L, 6000L);
        OrderItem s2 = item(2L, 4000L);
        Order order = orderWith(s1, s2);   // PENDING(결제 전)
        order.applyCoupon("WELCOME1000", 1000L, "PLATFORM", null);
        ReflectionTestUtils.setField(s1, "id", 500L);
        ReflectionTestUtils.setField(s2, "id", 501L);

        assertThatThrownBy(() -> order.cancelItem(501L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("결제 전 주문은 항목별 취소");

        // 막지 않았다면: 4000 취소 후 payable = 6000 − 600(안분 몫) = 5400 → 쿠폰 1000원을 다 못 받음(400원 과다청구).
        assertThat(order.getPayableAmount()).isEqualTo(9000L);   // 두 항목 그대로 = 10000 − 1000
        assertThat(s2.isActive()).isTrue();
    }

    @Test
    @DisplayName("cancelItem - 셀러의 마지막 활성 항목 취소 시 그 shipment도 CANCELLED, 전부 취소면 주문 CANCELLED")
    void cancelItem_lastSellerItem_cancelsShipmentAndOrder() {
        OrderItem s1 = item(1L, 5000L);
        OrderItem s2 = item(2L, 4000L);
        Order order = orderWith(s1, s2);
        order.markPaid();
        ReflectionTestUtils.setField(s1, "id", 500L);
        ReflectionTestUtils.setField(s2, "id", 501L);

        order.cancelItem(500L, 100L);   // 셀러1 마지막 항목
        assertThat(shipmentOfSeller(order, 1L).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);   // 셀러2 아직 PAID

        order.cancelItem(501L, 100L);   // 셀러2 마지막 항목 → 전부 취소
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    // === #3 P1: OrderItem RETURNED 상태 =============================================

    @Test
    @DisplayName("markReturned - ACTIVE 항목만 RETURNED로, 비활성 항목은 409")
    void markReturned_activeOnly() {
        OrderItem a = item(1L, 5000L);
        a.markReturned();
        assertThat(a.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
        assertThat(a.isActive()).isFalse();   // RETURNED는 비활성 → 정산 자동 상계

        assertThatThrownBy(a::markReturned)   // 이중 반품 차단
                .isInstanceOf(BusinessException.class).hasMessageContaining("반품할 수 없");
    }

    @Test
    @DisplayName("취소/반품 상호 배타 - 취소된 항목은 반품 불가, 반품된 항목은 취소 불가(이중 원장 차단)")
    void cancelReturn_mutuallyExclusive() {
        OrderItem cancelled = item(1L, 5000L);
        cancelled.cancel();
        assertThatThrownBy(cancelled::markReturned).isInstanceOf(BusinessException.class);

        OrderItem returned = item(2L, 4000L);
        returned.markReturned();
        assertThatThrownBy(returned::cancel).isInstanceOf(BusinessException.class);
    }
}
