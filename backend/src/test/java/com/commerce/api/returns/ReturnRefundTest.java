package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderItemStatus;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.product.entity.StockReservation;
import com.commerce.api.product.entity.StockReservationStatus;
import com.commerce.api.product.repository.StockReservationRepository;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.service.ReturnService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품 검수확정 환불(#3 P4) 통합 테스트 — 환불액·OrderItem RETURNED flip·재입고·결제 원장·주문 상태.
 */
@SpringBootTest
@Transactional
class ReturnRefundTest {

    @Autowired private ReturnService returnService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private StockReservationRepository stockReservationRepository;

    private ReturnStatusUpdateRequest act(ReturnAction a) {
        return new ReturnStatusUpdateRequest(a, null);
    }

    /** 셀러1 항목(price·discount) 배송완료 주문 + PAID 결제 + CONSUMED 예약 세팅 → [orderId, orderItemId, paymentId, reservationId]. */
    private long[] deliveredWithPaymentAndReservation(long price, long discount) {
        Order order = Order.create(100L);
        OrderItem item = OrderItem.builder().productId(1L).optionId(11L).sellerId(1L)
                .productName("P").size("M").orderPrice(price).quantity(1).build();
        order.addItem(item);
        if (discount > 0) {
            order.applyCoupon("C", discount, "PLATFORM", null);
        }
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1");
        order.advanceShipping(OrderStatus.DELIVERED, null, null, null);
        Order saved = orderRepository.saveAndFlush(order);
        long orderId = saved.getId();
        long itemId = saved.getOrderItems().get(0).getId();

        long payable = price - discount;
        Payment payment = Payment.ready(orderId, payable, "MOCK", "TOSS", "key-r-" + orderId);
        payment.markPaid("TOSS-tx-" + orderId);
        paymentRepository.saveAndFlush(payment);

        StockReservation res = StockReservation.active(orderId, itemId, 11L, 1, LocalDateTime.now().plusMinutes(30));
        res.markConsumed();   // 결제로 실차감된 상태(반품 재입고 대상)
        stockReservationRepository.saveAndFlush(res);

        return new long[] { orderId, itemId, payment.getId(), res.getId() };
    }

    private ReturnResponse advanceToInspected(long orderId, long itemId, ReturnType type, Long exchangeOptionId) {
        ReturnResponse req = returnService.create(100L, false, orderId,
                new ReturnCreateRequest(itemId, type, "변심", exchangeOptionId));
        returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.APPROVE), 1L);
        returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.PICK_UP), 1L);
        returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.INSPECT), 1L);
        return req;
    }

    @Test
    @DisplayName("검수확정 환불 e2e - 환불액=실효가·OrderItem RETURNED·예약 RELEASED(재입고)·결제 누적·주문 DELIVERED 유지")
    void refund_e2e() {
        long[] ids = deliveredWithPaymentAndReservation(5000L, 0L);
        long orderId = ids[0], itemId = ids[1], paymentId = ids[2], resId = ids[3];
        ReturnResponse req = advanceToInspected(orderId, itemId, ReturnType.RETURN, null);

        ReturnResponse refunded = returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.REFUND), 1L);

        assertThat(refunded.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(refunded.refundAmount()).isEqualTo(5000L);
        // 결제 원장: 전액 환불 → CANCELLED
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getRefundedAmount()).isEqualTo(5000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        // OrderItem RETURNED + 주문은 여전히 DELIVERED(shipment 불변, RETURNED는 상태축과 무관)
        Order after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.requireItem(itemId).getStatus()).isEqualTo(OrderItemStatus.RETURNED);
        assertThat(after.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        // 재입고: CONSUMED 예약이 RELEASED로(undoForOrderItem 실행됨)
        assertThat(stockReservationRepository.findById(resId).orElseThrow().getStatus())
                .isEqualTo(StockReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("환불액은 gross가 아니라 실효가(소계−안분할인) — 과다환불 아님")
    void refund_effectivePrice() {
        long[] ids = deliveredWithPaymentAndReservation(5000L, 1000L);   // 실효가 4000
        long orderId = ids[0], itemId = ids[1], paymentId = ids[2];
        ReturnResponse req = advanceToInspected(orderId, itemId, ReturnType.RETURN, null);

        ReturnResponse refunded = returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.REFUND), 1L);

        assertThat(refunded.refundAmount()).isEqualTo(4000L);   // gross 5000이 아니라 실효가 4000
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getRefundedAmount()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("타입 가드 - 교환(EXCHANGE) 요청에 REFUND 액션은 409(flip·PG 이전 조기 차단)")
    void refund_typeMismatch() {
        long[] ids = deliveredWithPaymentAndReservation(5000L, 0L);
        long orderId = ids[0], itemId = ids[1];
        ReturnResponse req = advanceToInspected(orderId, itemId, ReturnType.EXCHANGE, 22L);

        assertThatThrownBy(() -> returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.REFUND), 1L))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }
}
