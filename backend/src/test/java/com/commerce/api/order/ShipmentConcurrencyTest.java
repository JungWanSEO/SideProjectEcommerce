package com.commerce.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.repository.ShipmentRepository;
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
 * shipment 전진 동시성 통합 테스트(#1 P3).
 *
 * <p>셀러 A·B가 같은 주문의 서로 다른 shipment를 동시에 전진할 때, 저장된 파생 {@code Order.status}가 rollup으로
 * 정확히 수렴하는지 검증한다. 핵심 함정: rollup write가 <b>조건부</b>(값이 바뀔 때만)라, 낙관락만으론 두 tx가 각자
 * 형제 shipment의 커밋 전 상태를 읽어 "변화 없음"으로 판단→주문이 SHIPPING에 갇히는 lost update가 난다.
 * 부모 주문 낙관 버전 강제 증가 + @Retryable이 이를 충돌시켜 재시도가 fresh 컨텍스트로 재계산하게 한다.
 */
@SpringBootTest
class ShipmentConcurrencyTest {

    @Autowired private ShipmentService shipmentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShipmentRepository shipmentRepository;

    /** 셀러 2명(1·2)이 섞인 PAID 주문을 저장하고 [orderId, shipmentA, shipmentB]를 돌려준다. */
    private long[] persistPaidTwoSellerOrder() {
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).sellerId(1L).productName("A").size("M").orderPrice(5000L).quantity(1).build());
        order.addItem(OrderItem.builder()
                .productId(2L).optionId(22L).sellerId(2L).productName("B").size("L").orderPrice(4000L).quantity(1).build());
        order.markPaid();   // shipment 2건(PAID) 팬아웃
        Order saved = orderRepository.saveAndFlush(order);
        List<Shipment> ships = shipmentRepository.findByOrderId(saved.getId());
        return new long[] { saved.getId(), ships.get(0).getId(), ships.get(1).getId() };
    }

    private OrderStatus statusOf(long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("동시 출고 시작 - 두 shipment SHIPPING + 주문 SHIPPING으로 수렴")
    void concurrentShipping_convergesToShipping() throws InterruptedException {
        long[] ids = persistPaidTwoSellerOrder();
        long orderId = ids[0];
        long shipA = ids[1];
        long shipB = ids[2];

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
        long[] ids = persistPaidTwoSellerOrder();
        long orderId = ids[0];
        long shipA = ids[1];
        long shipB = ids[2];
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
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DELIVERED);   // 강제증가+재시도로 정확히 수렴
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
