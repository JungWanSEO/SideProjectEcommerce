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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송비(#4) 취소·환불 end-to-end 통합 테스트 — 실제 PaymentService(MOCK PG)로 배송비가
 * 오너 규칙대로 환불/유지되는지 검증한다. 배송비는 order.assignShippingFee로 직접 스냅샷(정책 계산은
 * ShippingPolicyTest, payable 접기는 OrderTest가 담당).
 *
 * <p>규칙: payable = 소계 + 배송비. 전체취소 → 배송비까지 환불 / 부분취소 → 배송비 유지 / 마지막 항목 취소로
 * 전체 취소되면 배송비까지 환불(잔여-활성 공식 통일).
 *
 * <p>{@code @Transactional}으로 롤백 격리 — 부분취소 케이스가 PAID 결제를 커밋해두면 정산 계열 테스트가 그
 * 잔재를 주워 오염된다(동시성 테스트와 달리 여긴 단일 스레드라 롤백 격리가 안전).
 */
@SpringBootTest
@Transactional
class ShippingFeeTest {

    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    private static final long SHIPPING = 3000L;

    /** 셀러1(10000)+셀러2(20000) 항목 + 배송비 3000 → payable 33000 PAID 주문/결제 생성. */
    private long[] paidOrderWithShipping() {
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).sellerId(1L).productName("A").size("M").orderPrice(10000L).quantity(1).build());
        order.addItem(OrderItem.builder()
                .productId(2L).optionId(22L).sellerId(2L).productName("B").size("L").orderPrice(20000L).quantity(1).build());
        order.assignShippingFee(SHIPPING);
        order.markPaid();
        Order saved = orderRepository.saveAndFlush(order);
        long orderId = saved.getId();

        assertThat(saved.getPayableAmount()).isEqualTo(33000L);   // 30000 + 배송비

        Payment payment = Payment.ready(orderId, 33000L, "MOCK_CARD", "TOSS", "key-ship-" + orderId);
        payment.markPaid("TOSS-tx-" + orderId);
        paymentRepository.saveAndFlush(payment);

        long itemA = itemIdOf(saved, 1L);
        long itemB = itemIdOf(saved, 2L);
        return new long[] { orderId, itemA, itemB, payment.getId() };
    }

    @Test
    @DisplayName("전체취소 - 배송비까지 전액 환불(payable 0), 결제 CANCELLED")
    void fullCancel_refundsShipping() {
        long[] ids = paidOrderWithShipping();
        long orderId = ids[0], paymentId = ids[3];

        paymentService.cancelOrder(100L, orderId, false);

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.getRefundedAmount()).isEqualTo(33000L);            // 소계 30000 + 배송비 3000
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("부분취소(한 항목) - 그 항목 실효가만 환불, 배송비는 유지(결제 PAID)")
    void partialCancel_retainsShipping() {
        long[] ids = paidOrderWithShipping();
        long orderId = ids[0], itemA = ids[1], paymentId = ids[3];

        paymentService.cancelOrderItem(100L, orderId, itemA, false);

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.getRefundedAmount()).isEqualTo(10000L);            // A만, 배송비 유지
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("마지막 항목까지 취소 - 잔여-활성 공식으로 배송비까지 환불, 결제 CANCELLED")
    void cancelAllItemsOneByOne_refundsShippingAtEnd() {
        long[] ids = paidOrderWithShipping();
        long orderId = ids[0], itemA = ids[1], itemB = ids[2], paymentId = ids[3];

        paymentService.cancelOrderItem(100L, orderId, itemA, false);   // 10000 환불(배송비 유지)
        paymentService.cancelOrderItem(100L, orderId, itemB, false);   // 20000 + 배송비 3000 환불

        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.getRefundedAmount()).isEqualTo(33000L);            // 전액(배송비 포함) 도달
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private long itemIdOf(Order order, Long sellerId) {
        return order.getOrderItems().stream()
                .filter(i -> sellerId.equals(i.getSellerId())).findFirst().orElseThrow().getId();
    }
}
