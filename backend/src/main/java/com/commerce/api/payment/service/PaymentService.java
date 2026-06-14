package com.commerce.api.payment.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.dto.PaymentRequest;
import com.commerce.api.payment.dto.PaymentResponse;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.gateway.PaymentGateway.PaymentApprovalCommand;
import com.commerce.api.payment.gateway.PaymentGateway.PaymentRefundCommand;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.gateway.PaymentRefund;
import com.commerce.api.payment.gateway.PaymentRoutingResult;
import com.commerce.api.payment.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 오케스트레이터.
 *
 * <p>흐름: 멱등성 확인 → 주문 검증(본인·PENDING) → PG 승인(모의) → 승인 시 재고 차감+주문 PAID → 결제 PAID.
 *
 * <p><b>의도적으로 @Transactional을 두지 않는다.</b> 재고 차감은 {@link OrderService#pay}(자체 @Transactional
 * + @Retryable)에 위임하는데, 이 메서드를 트랜잭션으로 감싸면 내부 호출이 같은 트랜잭션에 합류해
 * "새 트랜잭션으로 재시도"가 깨진다(낙관적 락 재시도는 트랜잭션 바깥에서 새 트랜잭션을 열어야 함).
 * 결제 저장은 {@code paymentRepository.save}가 각자 트랜잭션으로 처리한다.
 *
 * <p>결제 PAID 저장은 {@link PaymentCompletionRecorder}로 위임해 <b>결제 저장 + PAYMENT_COMPLETED 이벤트
 * 기록을 한 트랜잭션</b>으로 묶는다(트랜잭셔널 아웃박스 — docs/event-outbox-design.md). 주문 PAID와 결제 PAID의
 * 크로스 애그리거트 정합성은 여전히 대사(reconciliation)가 사후 검증한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final PaymentGatewayRouter paymentGatewayRouter;
    private final PaymentCompletionRecorder paymentCompletionRecorder;

    public PaymentResponse pay(Long memberId, PaymentRequest request) {
        // 1) 멱등성: 같은 키로 이미 처리된 결제가 있으면 재실행 없이 그 결과를 반환
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return PaymentResponse.from(existing.get());
        }

        // 2) 주문 검증: 존재 + 본인(아니면 OrderService가 404/403) + 결제 가능(PENDING)
        OrderResponse order = orderService.getOrder(request.orderId(), memberId, false);
        if (order.status() != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "결제할 수 없는 주문 상태입니다. (현재: " + order.status() + ")");
        }

        long amount = order.totalPrice();
        String method = (request.method() == null || request.method().isBlank()) ? "MOCK_CARD" : request.method();

        // 3) PG 승인 요청 — 클라이언트가 고른 PG로 시도하되, 그 PG가 장애·거절이면 다른 PG로 자동 페일오버
        //    (MPG-stretch, 정책은 라우터에 가둠). 미지원 PG면 여기서 400. 실제 승인한 provider를 결제에 기록한다.
        PaymentRoutingResult routed = paymentGatewayRouter.approveWithFailover(
                request.provider(), new PaymentApprovalCommand(order.id(), amount, request.idempotencyKey()));
        Payment payment = Payment.ready(order.id(), amount, method, routed.provider(), request.idempotencyKey());
        if (!routed.approval().approved()) {
            payment.markFailed();
            paymentRepository.save(payment);
            throw new BusinessException(HttpStatus.PAYMENT_REQUIRED,
                    "결제가 거절되었습니다. (" + routed.approval().failureReason() + ")");
        }

        // 4) 승인 성공 → 재고 차감 + 주문 PAID (낙관적 락 재시도 포함). 재고 부족 등 실패면 결제 FAILED로 기록 후 전파.
        try {
            orderService.pay(order.id());
        } catch (RuntimeException e) {
            payment.markFailed();
            paymentRepository.save(payment);
            throw e;   // 주문은 PENDING으로 남는다(재고 보충 후 재결제 가능)
        }
        // 승인·재고차감 성공 → 결제 PAID 저장 + PAYMENT_COMPLETED 이벤트를 한 트랜잭션으로(아웃박스).
        payment.markPaid(routed.approval().pgTransactionId());
        paymentCompletionRecorder.saveWithEvent(payment);
        return PaymentResponse.from(payment);
    }

    /**
     * 주문 취소(+환불) 오케스트레이터.
     *
     * <p>흐름: 주문 취소 위임(소유권·상태 검증 + PAID였으면 재고 복원) → 결제 완료(PAID) 건이 있으면 PG 환불 + 결제 CANCELLED.
     *
     * <p>취소도 {@code pay}처럼 결제(payment)가 주문(order)을 호출하는 한 방향으로 묶는다 — 주문이 결제를
     * 거꾸로 호출하면 순환 의존이 되기 때문(.NET DI로 치면 두 서비스가 서로를 생성자 주입하려다 터지는 상황).
     *
     * <p><b>{@code pay}와 달리 @Transactional로 감싼다.</b> 취소는 "새 트랜잭션 재시도"(낙관적 락)가 필요 없어
     * 원자성을 우선한다 — 환불이 실패하면 주문 취소·재고 복원까지 전부 롤백되어 "취소됐는데 환불 안 됨"을 막는다.
     * (모의 PG라 환불 호출이 즉시 끝난다. 실제 고지연 PG라면 환불을 트랜잭션 밖으로 빼고 이벤트/아웃박스로
     * 보강한다 — architecture.md §13.5.)
     */
    @Transactional
    public OrderResponse cancelOrder(Long memberId, Long orderId, boolean admin) {
        // 1) 주문 취소 위임 — 소유권(404/403) + 상태 가드(이미 취소면 409) + PAID였으면 재고 복원.
        //    무효한 요청이면 여기서 예외가 나 환불을 시도하지 않는다.
        OrderResponse cancelled = orderService.cancel(orderId, memberId, admin);

        // 2) 결제 완료(PAID) 건이 있으면 환불. PENDING 주문은 결제 레코드가 없으므로 환불 대상이 없다.
        //    (주문이 CANCELLED로 바뀌면 재취소가 409로 막히므로 중복 환불도 함께 방지된다.)
        paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.PAID)
                .ifPresent(payment -> {
                    // 환불은 반드시 승인한 그 PG로 — 결제에 저장된 provider로 라우팅한다.
                    PaymentRefund refund = paymentGatewayRouter.resolve(payment.getProvider())
                            .refund(new PaymentRefundCommand(
                                    orderId, payment.getAmount(), payment.getPgTransactionId()));
                    if (!refund.refunded()) {
                        throw new BusinessException(HttpStatus.BAD_GATEWAY,
                                "환불에 실패했습니다. (" + refund.failureReason() + ")");
                    }
                    payment.cancel();   // PAID → CANCELLED (상태머신 가드)
                    paymentRepository.save(payment);
                });

        return cancelled;
    }

    /**
     * 주문 항목 단위 취소(+부분 환불) 오케스트레이터.
     *
     * <p>흐름: 항목 취소 위임(OrderService.cancelItem — 소유권·상태 검증 + PAID였으면 그 항목 재고 복원) →
     * 결제 완료(PAID) 건이 있으면 그 항목 금액만큼 PG 부분 환불 + Payment.refundedAmount 누적(전액 환불 시 CANCELLED).
     * cancelOrder와 같이 @Transactional로 원자성 보장(환불 실패 시 항목 취소·재고 복원까지 롤백).
     * 정산 상계(역분개)는 settlement 도메인의 reverseRefunds 배치가 사후 처리한다(settlement → order/payment 단방향).
     */
    @Transactional
    public OrderResponse cancelOrderItem(Long memberId, Long orderId, Long orderItemId, boolean admin) {
        OrderResponse order = orderService.cancelItem(orderId, orderItemId, memberId, admin);
        OrderResponse.OrderItemResponse cancelledItem = order.items().stream()
                .filter(i -> orderItemId.equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."));
        long refundAmount = cancelledItem.subtotal();

        paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.PAID)
                .ifPresent(payment -> {
                    PaymentRefund refund = paymentGatewayRouter.resolve(payment.getProvider())
                            .refund(new PaymentRefundCommand(orderId, refundAmount, payment.getPgTransactionId()));
                    if (!refund.refunded()) {
                        throw new BusinessException(HttpStatus.BAD_GATEWAY,
                                "환불에 실패했습니다. (" + refund.failureReason() + ")");
                    }
                    payment.partialRefund(refundAmount);   // 누적, 전액 도달 시 CANCELLED
                    paymentRepository.save(payment);
                });
        return order;
    }

    /**
     * 결제 완료(PAID) 건 전체를 DTO로 반환한다 — 정산 도메인이 정산 대상 결제를 가져갈 때 쓴다.
     *
     * <p>settlement → payment 의존을 서비스 계층 + DTO로만 노출해(엔티티·리포지토리를 직접 안 넘김)
     * 도메인 경계를 지킨다(add-domain 컨벤션). PaymentResponse에 정산에 필요한 amount·orderId·
     * pgTransactionId가 모두 들어 있다.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaidPayments() {
        return paymentRepository.findByStatus(PaymentStatus.PAID).stream()
                .map(PaymentResponse::from)
                .toList();
    }
}
