package com.commerce.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.repository.ShipmentRepository;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.order.service.ShipmentService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * shipment 상태 변경 동시성 통합 테스트(#1 P3 + 리뷰 교정).
 *
 * <p>같은 주문의 서로 다른 자식(shipment/항목)을 동시에 바꿀 때 저장된 파생 {@code Order.status}가 rollup으로
 * 정확히 수렴하는지 검증한다. 핵심 함정: rollup write가 <b>조건부</b>(값이 바뀔 때만)라, 락 없이는 두 tx가 각자
 * 형제 shipment의 커밋 전 상태를 읽어 "변화 없음"으로 판단→상태가 갇히는 lost update가 난다. 모든 상태 변경 경로
 * (전진·취소·ADMIN 일괄)가 부모 주문 <b>비관적 쓰기 락</b>({@link OrderRepository#findByIdForUpdate})으로 직렬화된다.
 * 전진끼리(worker) 뿐 아니라 <b>전진×취소·취소×취소</b>(다른 경로) 교차도 함께 검증한다(리뷰 #1·#3).
 */
@SpringBootTest
class ShipmentConcurrencyTest {

    @Autowired private ShipmentService shipmentService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShipmentRepository shipmentRepository;

    /** 셀러 2명(1·2)이 섞인 PAID 주문을 저장해 관리 엔티티(항목·shipment id 포함)를 돌려준다. */
    private Order persistPaidTwoSellerOrder() {
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).sellerId(1L).productName("A").size("M").orderPrice(5000L).quantity(1).build());
        order.addItem(OrderItem.builder()
                .productId(2L).optionId(22L).sellerId(2L).productName("B").size("L").orderPrice(4000L).quantity(1).build());
        order.markPaid();   // shipment 2건(PAID) 팬아웃
        return orderRepository.saveAndFlush(order);
    }

    private long shipmentIdOf(Order order, Long sellerId) {
        return order.getShipments().stream().filter(s -> s.belongsToSeller(sellerId)).findFirst().orElseThrow().getId();
    }

    private long itemIdOf(Order order, Long sellerId) {
        return order.getOrderItems().stream()
                .filter(i -> sellerId.equals(i.getSellerId())).findFirst().orElseThrow().getId();
    }

    private OrderStatus statusOf(long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("동시 출고 시작 - 두 shipment SHIPPING + 주문 SHIPPING으로 수렴")
    void concurrentShipping_convergesToShipping() throws InterruptedException {
        Order order = persistPaidTwoSellerOrder();
        long orderId = order.getId();
        long shipA = shipmentIdOf(order, 1L);
        long shipB = shipmentIdOf(order, 2L);

        runConcurrently(
                () -> shipmentService.advance(shipA, ShipmentStatus.SHIPPING, 1L, "CJ", "A1"),
                () -> shipmentService.advance(shipB, ShipmentStatus.SHIPPING, 2L, "CJ", "B1"));

        assertThat(shipmentRepository.findByOrderId(orderId))
                .allMatch(s -> s.getStatus() == ShipmentStatus.SHIPPING);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("동시 배송 완료 - stale sibling에도 주문이 DELIVERED로 수렴(SHIPPING에 안 갇힘)")
    void concurrentDeliver_convergesToDelivered() throws InterruptedException {
        Order order = persistPaidTwoSellerOrder();
        long orderId = order.getId();
        long shipA = shipmentIdOf(order, 1L);
        long shipB = shipmentIdOf(order, 2L);
        // 선행: 둘 다 SHIPPING(주문 SHIPPING)
        shipmentService.advance(shipA, ShipmentStatus.SHIPPING, 1L, "CJ", "A1");
        shipmentService.advance(shipB, ShipmentStatus.SHIPPING, 2L, "CJ", "B1");
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.SHIPPING);

        // 동시에 둘 다 DELIVERED — 각 tx가 형제를 stale(SHIPPING)로 보면 rollup이 SHIPPING에 머물 위험.
        runConcurrently(
                () -> shipmentService.advance(shipA, ShipmentStatus.DELIVERED, 1L, null, null),
                () -> shipmentService.advance(shipB, ShipmentStatus.DELIVERED, 2L, null, null));

        assertThat(shipmentRepository.findByOrderId(orderId))
                .allMatch(s -> s.getStatus() == ShipmentStatus.DELIVERED);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DELIVERED);   // 비관 락 직렬화로 정확히 수렴
    }

    @Test
    @DisplayName("동시 전진×취소(다른 경로, 리뷰 #1) - 셀러B 배송완료 + 셀러A 항목취소 → 주문 DELIVERED로 수렴")
    void concurrentAdvanceAndCancel_convergesToDelivered() throws InterruptedException {
        Order order = persistPaidTwoSellerOrder();
        long orderId = order.getId();
        long shipB = shipmentIdOf(order, 2L);
        long itemA = itemIdOf(order, 1L);
        // 선행: 셀러B SHIPPING → 주문 SHIPPING (셀러A는 PAID)
        shipmentService.advance(shipB, ShipmentStatus.SHIPPING, 2L, "CJ", "B1");

        // 동시: 셀러B 배송완료(전진 경로) + 셀러A 항목취소(취소 경로) — 서로 다른 경로가 같은 주문 락으로 직렬화돼야 한다.
        runConcurrently(
                () -> shipmentService.advance(shipB, ShipmentStatus.DELIVERED, 2L, null, null),
                () -> orderService.cancelItem(orderId, itemA, 100L, false));

        // 활성(비취소) shipment는 셀러B(DELIVERED)뿐 → 주문 DELIVERED. 락 없으면 SHIPPING에 영구 고착(리뷰 #1).
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("동시 취소×취소(리뷰 #3) - 두 셀러 항목을 동시 취소 → 주문 CANCELLED로 수렴")
    void concurrentCancelBothItems_convergesToCancelled() throws InterruptedException {
        Order order = persistPaidTwoSellerOrder();
        long orderId = order.getId();
        long itemA = itemIdOf(order, 1L);
        long itemB = itemIdOf(order, 2L);

        runConcurrently(
                () -> orderService.cancelItem(orderId, itemA, 100L, false),
                () -> orderService.cancelItem(orderId, itemB, 100L, false));

        // 두 항목·두 shipment 모두 취소 → 주문 CANCELLED. 락 없으면 PAID에 고착(쿠폰 미복원·구매 오집계, 리뷰 #3).
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    /** 태스크들을 동시 출발선에 세워 최대 경합으로 실행(풀 크기 = 태스크 수, ready 게이트 데드락 회피). */
    private void runConcurrently(Runnable... tasks) throws InterruptedException {
        int n = tasks.length;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (Runnable task : tasks) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(errors).as("동시 전이 중 예외 없어야 함").isEmpty();
    }
}
