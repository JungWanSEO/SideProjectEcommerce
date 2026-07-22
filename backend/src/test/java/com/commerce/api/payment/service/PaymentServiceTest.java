package com.commerce.api.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.dto.PaymentRequest;
import com.commerce.api.payment.dto.PaymentResponse;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.gateway.PaymentApproval;
import com.commerce.api.payment.gateway.PaymentGateway;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.gateway.PaymentRefund;
import com.commerce.api.order.entity.OrderItemStatus;
import com.commerce.api.payment.gateway.PaymentRoutingResult;
import com.commerce.api.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * PaymentService 단위 테스트 — 멱등성 / 주문검증 / PG승인 / 재고차감 위임.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderService orderService;
    @Mock
    private PaymentGatewayRouter paymentGatewayRouter;
    @Mock
    private PaymentGateway paymentGateway;   // 라우터가 골라주는 어댑터(resolve 반환)
    @Mock
    private PaymentCompletionRecorder paymentCompletionRecorder;
    @Mock
    private MemberCouponService memberCouponService;   // 취소 시 쿠폰 복원(release) — void, 검증 불필요

    @InjectMocks
    private PaymentService paymentService;

    private OrderResponse order(Long id, Long memberId, OrderStatus status, long total) {
        // 쿠폰 없음: discountAmount=0, payableAmount=total. PaymentService는 payableAmount로 결제한다.
        return new OrderResponse(id, memberId, status, total, 0L, total, null, List.of(), null,
                null, null, List.of(), LocalDateTime.now());
    }

    /** 쿠폰 할인이 적용된 주문(gross=total, 할인=discount → payable=total-discount). */
    private OrderResponse discountedOrder(Long id, Long memberId, OrderStatus status, long total, long discount) {
        return new OrderResponse(id, memberId, status, total, discount, total - discount, "WELCOME5000",
                List.of(), null, null, null, List.of(), LocalDateTime.now());
    }

    private PaymentRequest request() {
        return new PaymentRequest(1L, "key-1", "MOCK_CARD", "TOSS");
    }

    @Test
    @DisplayName("결제 성공 - 승인 + 재고차감(주문 PAID) + 결제 PAID")
    void pay_success() {
        given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderService.getOrder(1L, 100L, false)).willReturn(order(1L, 100L, OrderStatus.PENDING, 30000L));
        given(paymentGatewayRouter.approveWithFailover(eq("TOSS"), any()))
                .willReturn(new PaymentRoutingResult("TOSS", PaymentApproval.approved("MOCK-tx-1"), List.of("TOSS")));

        PaymentResponse response = paymentService.pay(100L, request());

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.pgTransactionId()).isEqualTo("MOCK-tx-1");
        assertThat(response.provider()).isEqualTo("TOSS");
        assertThat(response.amount()).isEqualTo(30000L);
        verify(orderService).pay(1L);                                   // 재고 차감 + 주문 PAID 위임
        verify(paymentCompletionRecorder).saveWithEvent(any(Payment.class)); // 결제 저장 + 아웃박스 이벤트(한 트랜잭션)
    }

    @Test
    @DisplayName("결제 - 쿠폰 할인 주문이면 payable(총액-할인)로 결제된다")
    void pay_chargesPayableAmountWhenDiscounted() {
        given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        // 총액 30000, 할인 5000 → 실제 결제액 25000
        given(orderService.getOrder(1L, 100L, false))
                .willReturn(discountedOrder(1L, 100L, OrderStatus.PENDING, 30000L, 5000L));
        given(paymentGatewayRouter.approveWithFailover(eq("TOSS"), any()))
                .willReturn(new PaymentRoutingResult("TOSS", PaymentApproval.approved("MOCK-tx-2"), List.of("TOSS")));

        PaymentResponse response = paymentService.pay(100L, request());

        assertThat(response.amount()).isEqualTo(25000L);   // 할인 반영(gross 30000이 아님)
    }

    @Test
    @DisplayName("페일오버 - 요청과 다른 PG로 승인되면 결제엔 실제 승인 PG가 기록된다(환불도 그 PG로)")
    void pay_failoverRecordsActualProvider() {
        given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderService.getOrder(1L, 100L, false)).willReturn(order(1L, 100L, OrderStatus.PENDING, 30000L));
        // 요청은 KAKAOPAY였지만 라우터가 TOSS로 페일오버해 승인
        given(paymentGatewayRouter.approveWithFailover(eq("KAKAOPAY"), any()))
                .willReturn(new PaymentRoutingResult("TOSS", PaymentApproval.approved("TOSS-tx-9"),
                        List.of("KAKAOPAY", "TOSS")));

        PaymentResponse response = paymentService.pay(100L,
                new PaymentRequest(1L, "key-1", "MOCK_CARD", "KAKAOPAY"));

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.provider()).isEqualTo("TOSS");             // 실제 승인 PG 기록
        assertThat(response.pgTransactionId()).isEqualTo("TOSS-tx-9");
    }

    @Test
    @DisplayName("멱등성 - 같은 키로 이미 결제됐으면 재실행 없이 기존 결과 반환")
    void pay_idempotentReplay() {
        Payment done = Payment.ready(1L, 30000L, "MOCK_CARD", "TOSS", "key-1");
        done.markPaid("MOCK-tx-1");
        given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(done));

        PaymentResponse response = paymentService.pay(100L, request());

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        verify(paymentGatewayRouter, never()).approveWithFailover(any(), any());   // PG 호출 자체를 안 함
        verify(orderService, never()).pay(any());                // 재고 재차감 없음
    }

    @Test
    @DisplayName("결제 실패 - 결제 가능 상태(PENDING)가 아니면 409, 승인·차감 안 함")
    void pay_notPending() {
        given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderService.getOrder(1L, 100L, false)).willReturn(order(1L, 100L, OrderStatus.PAID, 30000L));

        assertThatThrownBy(() -> paymentService.pay(100L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("결제할 수 없는 주문 상태");
        verify(paymentGatewayRouter, never()).approveWithFailover(any(), any());   // PG 호출 안 함
        verify(orderService, never()).pay(any());
    }

    @Test
    @DisplayName("결제 실패 - PG 거절이면 402, 결제 FAILED 저장, 재고 차감 안 함")
    void pay_gatewayDeclined() {
        given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderService.getOrder(1L, 100L, false)).willReturn(order(1L, 100L, OrderStatus.PENDING, 30000L));
        given(paymentGatewayRouter.approveWithFailover(eq("TOSS"), any()))
                .willReturn(new PaymentRoutingResult("TOSS", PaymentApproval.failed("한도 초과"), List.of("TOSS")));
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> paymentService.pay(100L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("결제가 거절");

        verify(orderService, never()).pay(any());
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);   // 실패 기록
    }

    @Test
    @DisplayName("결제 실패 - 승인됐지만 재고 부족이면 결제 FAILED 저장 후 예외 전파")
    void pay_insufficientStock() {
        given(paymentRepository.findByIdempotencyKey("key-1")).willReturn(Optional.empty());
        given(orderService.getOrder(1L, 100L, false)).willReturn(order(1L, 100L, OrderStatus.PENDING, 30000L));
        given(paymentGatewayRouter.approveWithFailover(eq("TOSS"), any()))
                .willReturn(new PaymentRoutingResult("TOSS", PaymentApproval.approved("MOCK-tx-1"), List.of("TOSS")));
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));
        willThrow(new BusinessException(HttpStatus.CONFLICT, "재고가 부족합니다."))
                .given(orderService).pay(1L);

        assertThatThrownBy(() -> paymentService.pay(100L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("재고가 부족합니다");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("취소 - PAID 주문이면 주문취소 위임 + PG환불 + 결제 CANCELLED")
    void cancelOrder_paidOrder_refunds() {
        given(orderService.cancel(1L, 100L, false))
                .willReturn(order(1L, 100L, OrderStatus.CANCELLED, 30000L));
        Payment paid = Payment.ready(1L, 30000L, "MOCK_CARD", "TOSS", "key-1");
        paid.markPaid("MOCK-tx-1");
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.of(paid));
        given(paymentGatewayRouter.resolve("TOSS")).willReturn(paymentGateway);   // 환불은 저장된 PG로 라우팅
        given(paymentGateway.refund(any())).willReturn(PaymentRefund.refunded("MOCK-REFUND-1"));
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));

        OrderResponse response = paymentService.cancelOrder(100L, 1L, false);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderService).cancel(1L, 100L, false);   // 재고 복원 + 주문 CANCELLED 위임
        verify(paymentGatewayRouter).resolve("TOSS");    // 승인한 PG로 환불 라우팅
        verify(paymentGateway).refund(any());
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.CANCELLED);   // 환불 반영
    }

    @Test
    @DisplayName("취소 - PENDING 주문(결제 없음)이면 환불 없이 주문만 취소")
    void cancelOrder_pendingOrder_noRefund() {
        given(orderService.cancel(1L, 100L, false))
                .willReturn(order(1L, 100L, OrderStatus.CANCELLED, 30000L));
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.empty());

        OrderResponse response = paymentService.cancelOrder(100L, 1L, false);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentGatewayRouter, never()).resolve(any());   // 환불 대상 없음 → PG 라우팅 안 함
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("취소 - PG 환불 실패면 502, 결제 CANCELLED 저장 안 함(트랜잭션 롤백 대상)")
    void cancelOrder_refundFails() {
        given(orderService.cancel(1L, 100L, false))
                .willReturn(order(1L, 100L, OrderStatus.CANCELLED, 30000L));
        Payment paid = Payment.ready(1L, 30000L, "MOCK_CARD", "TOSS", "key-1");
        paid.markPaid("MOCK-tx-1");
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.of(paid));
        given(paymentGatewayRouter.resolve("TOSS")).willReturn(paymentGateway);
        given(paymentGateway.refund(any())).willReturn(PaymentRefund.failed("PG 점검중"));

        assertThatThrownBy(() -> paymentService.cancelOrder(100L, 1L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("환불에 실패");
        verify(paymentRepository, never()).save(any());
    }

    // === 항목 부분취소(+부분환불) — cancelOrderItem ===

    /** 항목 1개(id=100, 소계=subtotal, 안분할인=discountShare)를 담은 주문 응답. */
    private OrderResponse orderWithItem(OrderStatus status, long subtotal, long discountShare) {
        var item = new OrderResponse.OrderItemResponse(
                100L, 1L, 10L, 7L, 3L, "반팔티셔츠", "M", 10000L, 3, subtotal, discountShare,
                OrderItemStatus.CANCELLED);
        return new OrderResponse(1L, 100L, status, subtotal, discountShare, subtotal - discountShare,
                discountShare > 0 ? "WELCOME5000" : null, List.of(item), null,
                null, null, List.of(), LocalDateTime.now());
    }

    @Test
    @DisplayName("항목 취소 - PAID 주문이면 항목 실효가(소계-안분할인)만큼 부분환불하고 결제에 누적한다")
    void cancelOrderItem_refundsEffectivePrice() {
        // 소계 30000, 안분 할인 5000 → 실효가 25000만 환불(gross 30000 환불하면 과다환불)
        given(orderService.cancelItem(1L, 100L, 100L, false))
                .willReturn(orderWithItem(OrderStatus.PAID, 30000L, 5000L));
        Payment paid = Payment.ready(1L, 25000L, "MOCK_CARD", "TOSS", "key-1");
        paid.markPaid("MOCK-tx-1");
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.of(paid));
        given(paymentGatewayRouter.resolve("TOSS")).willReturn(paymentGateway);
        given(paymentGateway.refund(any())).willReturn(PaymentRefund.refunded("MOCK-refund-1"));

        paymentService.cancelOrderItem(100L, 1L, 100L, false);

        ArgumentCaptor<PaymentGateway.PaymentRefundCommand> cmd =
                ArgumentCaptor.forClass(PaymentGateway.PaymentRefundCommand.class);
        verify(paymentGateway).refund(cmd.capture());
        assertThat(cmd.getValue().amount()).isEqualTo(25000L);   // 실효가만 환불
        assertThat(paid.getRefundedAmount()).isEqualTo(25000L);  // 결제에 부분환불 누적
        verify(paymentRepository).save(paid);
    }

    @Test
    @DisplayName("항목 취소 - 부분환불이 PG에서 실패하면 502, 결제 저장 안 함(롤백 대상)")
    void cancelOrderItem_refundFails() {
        given(orderService.cancelItem(1L, 100L, 100L, false))
                .willReturn(orderWithItem(OrderStatus.PAID, 30000L, 0L));
        Payment paid = Payment.ready(1L, 30000L, "MOCK_CARD", "TOSS", "key-1");
        paid.markPaid("MOCK-tx-1");
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.of(paid));
        given(paymentGatewayRouter.resolve("TOSS")).willReturn(paymentGateway);
        given(paymentGateway.refund(any())).willReturn(PaymentRefund.failed("PG 점검중"));

        assertThatThrownBy(() -> paymentService.cancelOrderItem(100L, 1L, 100L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("항목 취소 - 마지막 항목 취소로 주문 전체가 CANCELLED면 발급형 쿠폰을 복원한다")
    void cancelOrderItem_wholeOrderCancelled_releasesCoupon() {
        // 항목 취소 결과 주문 status=CANCELLED (마지막 활성 항목이었음)
        given(orderService.cancelItem(1L, 100L, 100L, false))
                .willReturn(orderWithItem(OrderStatus.CANCELLED, 30000L, 5000L));
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.empty());

        paymentService.cancelOrderItem(100L, 1L, 100L, false);

        verify(memberCouponService).release(100L, "WELCOME5000");   // 취소된 주문의 쿠폰 복원
    }

    @Test
    @DisplayName("항목 취소 - PENDING 주문(결제 없음)이면 환불 없이 항목만 취소")
    void cancelOrderItem_pendingNoRefund() {
        given(orderService.cancelItem(1L, 100L, 100L, false))
                .willReturn(orderWithItem(OrderStatus.PENDING, 30000L, 0L));
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.empty());

        paymentService.cancelOrderItem(100L, 1L, 100L, false);

        verify(paymentGateway, never()).refund(any());
        verify(paymentRepository, never()).save(any());
    }

    // === 회귀: 항목 CANCELLED 상태를 취소/환불 경로가 일관되게 존중하는가 ===

    @Test
    @DisplayName("회귀(과다환불 방지) - 부분환불이 있던 결제를 전체취소하면 '잔여'만 환불한다(전액 재환불 아님)")
    void cancelOrder_afterPartialRefund_refundsOnlyRemaining() {
        given(orderService.cancel(1L, 100L, false))
                .willReturn(order(1L, 100L, OrderStatus.CANCELLED, 30000L));
        // 결제 30000 중 이미 10000을 항목단위로 환불한 상태(refundedAmount=10000, 아직 PAID)
        Payment paid = Payment.ready(1L, 30000L, "MOCK_CARD", "TOSS", "key-1");
        paid.markPaid("MOCK-tx-1");
        paid.partialRefund(10000L);
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.of(paid));
        given(paymentGatewayRouter.resolve("TOSS")).willReturn(paymentGateway);
        given(paymentGateway.refund(any())).willReturn(PaymentRefund.refunded("MOCK-REFUND-2"));

        paymentService.cancelOrder(100L, 1L, false);

        ArgumentCaptor<PaymentGateway.PaymentRefundCommand> cmd =
                ArgumentCaptor.forClass(PaymentGateway.PaymentRefundCommand.class);
        verify(paymentGateway).refund(cmd.capture());
        assertThat(cmd.getValue().amount()).isEqualTo(20000L);   // 전액 30000이 아니라 잔여 20000만
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.CANCELLED);   // 잔여까지 환불돼 전액 도달
        assertThat(paid.getRefundedAmount()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("회귀(과다환불 방지) - 모든 항목이 이미 부분환불로 소진됐으면 전체취소 시 추가 환불 없음")
    void cancelOrder_alreadyFullyRefunded_noExtraRefund() {
        given(orderService.cancel(1L, 100L, false))
                .willReturn(order(1L, 100L, OrderStatus.CANCELLED, 30000L));
        Payment paid = Payment.ready(1L, 30000L, "MOCK_CARD", "TOSS", "key-1");
        paid.markPaid("MOCK-tx-1");
        paid.partialRefund(30000L);   // 이미 전액 환불 → CANCELLED (findByOrderIdAndStatus(PAID) 못 찾음이 정상)
        // PAID로 조회되지 않으므로(이미 CANCELLED) 환불 시도 자체가 없다
        given(paymentRepository.findByOrderIdAndStatus(1L, PaymentStatus.PAID)).willReturn(Optional.empty());

        paymentService.cancelOrder(100L, 1L, false);

        verify(paymentGateway, never()).refund(any());
    }

    @Test
    @DisplayName("회귀(0원 환불) - 실효가 0인 라인(100% 할인) 취소는 PG 환불 없이 통과(400 롤백 방지)")
    void cancelOrderItem_zeroEffectivePrice_skipsRefund() {
        // 소계 30000이 전액(30000) 할인 안분 → 실효가 0
        given(orderService.cancelItem(1L, 100L, 100L, false))
                .willReturn(orderWithItem(OrderStatus.PAID, 30000L, 30000L));
        // 실효가 0이면 결제 조회조차 하지 않는다(refundAmount>0 가드가 블록 전체를 건너뜀).

        // 예외 없이 통과해야 한다(partialRefund(0)이 400을 던지면 정당한 취소가 롤백됨)
        paymentService.cancelOrderItem(100L, 1L, 100L, false);

        verify(paymentRepository, never()).findByOrderIdAndStatus(any(), any());
        verify(paymentGateway, never()).refund(any());
        verify(paymentRepository, never()).save(any());
    }
}
