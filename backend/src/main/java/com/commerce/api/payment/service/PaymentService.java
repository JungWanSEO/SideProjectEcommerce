package com.commerce.api.payment.service;

import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.global.common.CancelReason;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.service.OrderEventEmitter;
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
import org.springframework.transaction.annotation.Isolation;
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
    private final MemberCouponService memberCouponService;   // 주문 취소 시 발급형 쿠폰 복원
    private final OrderEventEmitter orderEventEmitter;   // 전체 취소 시 구매자 알림 이벤트(#6 P2b)

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

        // 결제액은 쿠폰 할인을 반영한 payable(= 총액 - 할인액). 고객이 실제로 내는 금액이 PG로 간다.
        long amount = order.payableAmount();
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse cancelOrder(Long memberId, Long orderId, boolean admin, CancelReason reason) {
        // 1) 주문 취소 위임 — 소유권(404/403) + 상태 가드 + 취소된 항목만 재고 되돌림. 멀티셀러(#1 c안)에선
        //    출고 전 항목만 취소되는 <b>부분 취소</b>일 수 있다(출고된 셀러 항목은 남는다). 사유는 항목에 기록(#8).
        OrderResponse cancelled = orderService.cancel(orderId, memberId, admin, reason);
        // 발급형 쿠폰 복원은 주문이 <b>전부</b> 취소됐을 때만 — 부분 취소면 남은(출고된) 항목에 쿠폰이 유효하다.
        if (cancelled.status() == OrderStatus.CANCELLED) {
            memberCouponService.release(cancelled.memberId(), cancelled.couponCode());
            orderEventEmitter.emitOrderStatusChanged(orderId, cancelled.memberId(), OrderStatus.CANCELLED);   // 구매자 취소·환불 알림(#6 P2b)
        }

        // 2) 결제 완료(PAID) 건이 있으면 <b>이번에 취소된 항목의 실효가 합만</b> 환불한다(#1 P4, 과다환불 차단).
        //    = (잔여 결제액) − (취소 후에도 남은 활성 항목 실효가 합). 전량 취소면 남은 활성 0이라 잔여 전액,
        //    부분 취소(출고분 잔존)면 그만큼 적게 환불해 출고된 셀러 몫까지 재환불되는 것을 막는다.
        paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.PAID)
                .ifPresent(payment -> {
                    long remainingActive = cancelled.payableAmount();   // 취소 후 남은 활성 항목 실효가 합
                    long refundNow = (payment.getAmount() - payment.getRefundedAmount()) - remainingActive;
                    if (refundNow <= 0) {
                        return;   // 돌려줄 잔여 없음(전액 환불됐거나 남은 활성이 잔여와 같음)
                    }
                    // 환불은 반드시 승인한 그 PG로 — 결제에 저장된 provider로 라우팅한다.
                    PaymentRefund refund = paymentGatewayRouter.resolve(payment.getProvider())
                            .refund(new PaymentRefundCommand(
                                    orderId, refundNow, payment.getPgTransactionId()));
                    if (!refund.refunded()) {
                        throw new BusinessException(HttpStatus.BAD_GATEWAY,
                                "환불에 실패했습니다. (" + refund.failureReason() + ")");
                    }
                    payment.partialRefund(refundNow);   // 누적 → refundedAmount==amount 도달 시 CANCELLED
                    paymentRepository.save(payment);
                });

        return cancelled;
    }

    /**
     * 주문 항목 단위 취소(+부분 환불) 오케스트레이터.
     *
     * <p>흐름: 항목 취소 위임(OrderService.cancelItem — 소유권·상태 검증 + PAID였으면 그 항목 재고 복원) →
     * 결제 완료(PAID) 건이 있으면 <b>잔여-활성 공식</b>(cancelOrder와 동일)으로 이번에 취소된 몫만 PG 부분 환불 +
     * Payment.refundedAmount 누적(전액 환불 시 CANCELLED). 마지막 항목 취소로 전체 취소되면 배송비까지 환불(#4).
     * cancelOrder와 같이 @Transactional로 원자성 보장(환불 실패 시 항목 취소·재고 복원까지 롤백).
     * 정산 상계(역분개)는 settlement 도메인의 reverseRefunds 배치가 사후 처리한다(settlement → order/payment 단방향).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse cancelOrderItem(Long memberId, Long orderId, Long orderItemId, boolean admin, CancelReason reason) {
        OrderResponse order = orderService.cancelItem(orderId, orderItemId, memberId, admin, reason);

        // 환불액 = (잔여 결제액) − (취소 후 남은 활성 payable). cancelOrder와 <b>같은 잔여-활성 공식으로 통일</b>한다(#4).
        //   이 공식은 이번에 취소된 항목의 실효가와 같되(무배송 세계선 기존 항목-실효가와 수학적 동치), 마지막 활성
        //   항목을 취소해 주문이 전체 취소되면 남은 payable이 0이 돼 <b>배송비까지 자동 환불</b>된다(오너 규칙: 전체취소만
        //   배송비 환불). 부분취소면 배송비가 남은 payable에 남아 환불에서 제외(유지). 실효가 0(100% 할인) 라인은
        //   refundNow<=0이라 PG·누적을 건너뛴다(partialRefund(0) 400 → 항목취소 롤백 회피).
        paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.PAID)
                .ifPresent(payment -> {
                    long remainingActive = order.payableAmount();   // 취소 후 남은 활성 실효가 + (활성 잔존 시)배송비
                    long refundNow = (payment.getAmount() - payment.getRefundedAmount()) - remainingActive;
                    if (refundNow <= 0) {
                        return;
                    }
                    PaymentRefund refund = paymentGatewayRouter.resolve(payment.getProvider())
                            .refund(new PaymentRefundCommand(orderId, refundNow, payment.getPgTransactionId()));
                    if (!refund.refunded()) {
                        throw new BusinessException(HttpStatus.BAD_GATEWAY,
                                "환불에 실패했습니다. (" + refund.failureReason() + ")");
                    }
                    payment.partialRefund(refundNow);   // 누적, 전액 도달 시 CANCELLED
                    paymentRepository.save(payment);
                });
        // 마지막 항목 취소로 주문 전체가 취소되면 발급형 쿠폰도 복원 + 구매자 알림.
        if (order.status() == OrderStatus.CANCELLED) {
            memberCouponService.release(order.memberId(), order.couponCode());
            orderEventEmitter.emitOrderStatusChanged(orderId, order.memberId(), OrderStatus.CANCELLED);   // 구매자 취소·환불 알림(#6 P2b)
        }
        return order;
    }

    /**
     * 반품 검수확정 환불(#3 P4) — cancelOrderItem의 환불 블록과 동형. 호출자(ReturnService)가 <b>이미 부모 주문
     * 비관락을 잡은 트랜잭션 안에서</b> 호출하므로 여기선 락을 다시 잡지 않는다(그 락이 Payment.refundedAmount
     * lost-update도 보호). 실효가 0(100% 할인) 라인은 PG·누적 스킵. 이 메서드는 반드시 OrderItem flip 이후에 호출한다
     * (flip이 먼저 검증돼야 환불이 롤백 없이 나간다 — 리뷰 HIGH 교정).
     */
    public void refundForReturn(Long orderId, long refundAmount) {
        if (refundAmount <= 0) {
            return;
        }
        paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.PAID)
                .ifPresent(payment -> {
                    PaymentRefund refund = paymentGatewayRouter.resolve(payment.getProvider())
                            .refund(new PaymentRefundCommand(orderId, refundAmount, payment.getPgTransactionId()));
                    if (!refund.refunded()) {
                        throw new BusinessException(HttpStatus.BAD_GATEWAY,
                                "환불에 실패했습니다. (" + refund.failureReason() + ")");
                    }
                    payment.partialRefund(refundAmount);   // 누적 → 전액 도달 시 CANCELLED
                    paymentRepository.save(payment);
                });
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

    /**
     * 정산 <b>역분개</b> 후보 — PAID + CANCELLED(#3 P5). 반품 전액환불로 CANCELLED된 결제까지 포함해야
     * reverseRefunds가 역분개를 놓치지 않는다(PAID만 스캔하면 셀러 과다정산). run()(정방향)은 계속 PAID만 쓴다.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getSettlementReversalCandidates() {
        return paymentRepository.findByStatusIn(List.of(PaymentStatus.PAID, PaymentStatus.CANCELLED)).stream()
                .map(PaymentResponse::from)
                .toList();
    }
}
