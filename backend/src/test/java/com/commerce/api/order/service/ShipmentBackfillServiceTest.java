package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.repository.ShipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * shipment 백필(#1 P2) 통합 테스트 — findPurchasedWithoutShipments 쿼리 + cascade 저장 + per-order 멱등을 DB로 검증.
 *
 * <p>레거시(P2 이전) 주문은 결제로 shipment가 생기지만 이를 지워 "shipment 없는 기존 PURCHASED 주문"을 만든다.
 * @Transactional 롤백이라 다른 테스트/커밋 데이터를 오염시키지 않으며, 특정 orderId로 단언해 교차 데이터에 견딘다.
 */
@SpringBootTest
@Transactional
class ShipmentBackfillServiceTest {

    @Autowired private ShipmentBackfillService backfillService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShipmentRepository shipmentRepository;

    private Order twoSellerOrder() {
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).sellerId(1L).productName("A").size("M").orderPrice(5000L).quantity(1).build());
        order.addItem(OrderItem.builder()
                .productId(2L).optionId(22L).sellerId(2L).productName("B").size("L").orderPrice(4000L).quantity(1).build());
        return order;
    }

    @Test
    @DisplayName("백필 - 레거시 PURCHASED 주문에 shipment 소급 생성, P2 이후 결제분은 건드리지 않음(멱등)")
    void backfillsLegacyOrdersOnly() {
        // 레거시: 결제로 생긴 shipment를 지워 "shipment 없는 기존 주문"으로 만든다
        Order legacy = twoSellerOrder();
        legacy.markPaid();
        orderRepository.saveAndFlush(legacy);
        legacy.getShipments().clear();
        orderRepository.saveAndFlush(legacy);   // orphanRemoval로 shipment 삭제 → 레거시 상태
        Long legacyId = legacy.getId();
        assertThat(shipmentRepository.findByOrderId(legacyId)).isEmpty();

        // 대조군: P2 이후 결제(shipment 이미 있음) → 백필이 중복 생성하면 안 됨
        Order fresh = twoSellerOrder();
        fresh.markPaid();
        orderRepository.saveAndFlush(fresh);
        Long freshId = fresh.getId();
        assertThat(shipmentRepository.findByOrderId(freshId)).hasSize(2);

        backfillService.backfillAll();

        assertThat(shipmentRepository.findByOrderId(legacyId))   // 소급 생성됨
                .hasSize(2)
                .extracting(s -> s.getSellerId())
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(shipmentRepository.findByOrderId(freshId)).hasSize(2);   // 중복 없음(원래대로)

        // 멱등: 재실행해도 legacy는 이미 shipment가 있어 더 늘지 않는다
        backfillService.backfillAll();
        assertThat(shipmentRepository.findByOrderId(legacyId)).hasSize(2);
    }

    @Test
    @DisplayName("백필 - PENDING(shipment 없음)은 대상 아님")
    void skipsPendingOrders() {
        Order pending = twoSellerOrder();   // 생성 직후 PENDING, shipment 없음
        orderRepository.saveAndFlush(pending);
        Long pendingId = pending.getId();

        backfillService.backfillAll();

        assertThat(shipmentRepository.findByOrderId(pendingId)).isEmpty();
    }
}
