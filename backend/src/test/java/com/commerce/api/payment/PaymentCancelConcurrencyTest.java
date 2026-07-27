package com.commerce.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.payment.service.PaymentService;
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
 * 항목 취소(부분환불) 동시성 통합 테스트(#1 리뷰 #2 교정).
 *
 * <p>멀티셀러 주문의 두 항목을 동시에 취소하면, 락이 없으면 두 tx가 같은 Payment를 refundedAmount=0으로 읽어
 * 둘 다 PG 환불을 내보내고 두 번째 커밋이 첫 번째를 덮어써 <b>환불액이 원장에 누락</b>(결제 CANCELLED 미도달·대사 붕괴)된다.
 * cancelOrderItem이 부모 주문을 비관적 락으로 잡아 <b>결제 원장 갱신까지 직렬화</b>되는지 검증한다.
 */
@SpringBootTest
class PaymentCancelConcurrencyTest {

    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    @DisplayName("동시 항목취소 - 두 항목 환불이 모두 원장에 누적(refundedAmount=결제액, 결제 CANCELLED)")
    void concurrentItemCancels_accumulateBothRefunds() throws InterruptedException {
        // 셀러1 항목(5000) + 셀러2 항목(4000), payable=9000 PAID 주문
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).sellerId(1L).productName("A").size("M").orderPrice(5000L).quantity(1).build());
        order.addItem(OrderItem.builder()
                .productId(2L).optionId(22L).sellerId(2L).productName("B").size("L").orderPrice(4000L).quantity(1).build());
        order.markPaid();
        Order saved = orderRepository.saveAndFlush(order);
        long orderId = saved.getId();
        long itemA = itemIdOf(saved, 1L);
        long itemB = itemIdOf(saved, 2L);

        Payment payment = Payment.ready(orderId, 9000L, "MOCK_CARD", "TOSS", "key-cc-" + orderId);
        payment.markPaid("TOSS-tx-" + orderId);
        paymentRepository.saveAndFlush(payment);

        runConcurrently(
                () -> paymentService.cancelOrderItem(100L, orderId, itemA, false, null),
                () -> paymentService.cancelOrderItem(100L, orderId, itemB, false, null));

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getRefundedAmount()).isEqualTo(9000L);            // 두 환불(5000+4000) 모두 누적 — lost update 없음
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CANCELLED);  // 전액 환불 도달 → CANCELLED
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private long itemIdOf(Order order, Long sellerId) {
        return order.getOrderItems().stream()
                .filter(i -> sellerId.equals(i.getSellerId())).findFirst().orElseThrow().getId();
    }

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
        assertThat(errors).as("동시 항목취소 중 예외 없어야 함").isEmpty();
    }
}
