package com.commerce.api.returns.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.service.PaymentService;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.repository.ProductOptionRepository;
import com.commerce.api.product.service.StockReservationService;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.repository.ReturnRequestRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품/교환 오케스트레이터(#3). 배송완료 후 다단계 워크플로를 API로 노출한다.
 *
 * <p>동시성: 모든 상태 변경은 <b>부모 주문 비관락</b>(OrderRepository.findByIdForUpdate)으로 먼저 잡아 직렬화한다
 * (#1 리뷰 교훈 — 락 순서는 항상 ORDER→RETURN로 데드락 회피). READ_COMMITTED로 락 이후 최신 상태를 읽는다.
 * 요청 생성도 부모 락을 잡아 구매자 더블클릭 중복요청 lost-insert를 막는다.
 *
 * <p>P3 범위: 요청·승인·거부·수거·검수(돈·재고 이동 없음). 환불(REFUND)·교환완료(COMPLETE)는 P4/P6에서 배선한다.
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    /** 반품 가능 기한(배송완료 후 일수) — 전자상거래법 기본 7일(v1). */
    static final int RETURN_WINDOW_DAYS = 7;

    /** 미종료 상태 — 진행 중 중복 반품 가드. */
    private static final List<ReturnStatus> NON_TERMINAL = List.of(
            ReturnStatus.REQUESTED, ReturnStatus.APPROVED, ReturnStatus.PICKED_UP, ReturnStatus.INSPECTED);

    private final OrderRepository orderRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final PaymentService paymentService;              // 반품 환불(#3 P4) — returns→payment 단방향
    private final StockReservationService stockReservationService;   // 반품 재입고·교환 재고(#3 P4/P6) — returns→product
    private final ProductOptionRepository productOptionRepository;   // 교환 대체 옵션 검증(#3 P6)
    private final ReturnEventEmitter returnEventEmitter;   // 상태 전이 → 구매자 알림 이벤트(#6 P2c)

    /**
     * 구매자 반품/교환 요청 생성. 부모 주문 비관락으로 중복요청을 직렬화하고, 대상 항목 자격(ACTIVE·배송완료·기한)을
     * 검증한 뒤 sellerId를 <b>서버가 도출</b>해 요청을 만든다(클라 sellerId 무시 — IDOR).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnResponse create(Long memberId, boolean admin, Long orderId, ReturnCreateRequest req) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
        if (!admin && !order.getMemberId().equals(memberId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인의 주문만 반품할 수 있습니다.");
        }
        Order.ReturnableItem ctx = order.ensureReturnable(req.orderItemId(), RETURN_WINDOW_DAYS);
        if (!returnRequestRepository.findByOrderItemIdAndStatusIn(req.orderItemId(), NON_TERMINAL).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 진행 중인 반품/교환이 있습니다.");
        }
        // 교환 완료 항목의 재-반품 차단(적대적리뷰 MED) — 교환 후 원 항목은 ACTIVE로 남고 자격 게이트는 원배송
        // (DELIVERED)만 보므로, 이 가드가 없으면 교환품을 받고도 다시 환불받는 이중지급이 뚫린다. 교환품 반품은 v1 미지원.
        if (returnRequestRepository.existsByOrderItemIdAndTypeAndStatus(
                req.orderItemId(), com.commerce.api.returns.entity.ReturnType.EXCHANGE, ReturnStatus.COMPLETED)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 교환 완료된 항목입니다. 교환품 반품은 고객센터로 문의해 주세요.");
        }
        // 소유권은 실제 구매자(order.memberId)로 귀속 — ADMIN 대행(admin=true) 생성 시에도 구매자가 자기 반품을
        // /returns/me로 조회·추적할 수 있게 한다(적대적리뷰 LOW: caller(admin) id로 귀속되던 문제 교정).
        ReturnRequest r = ReturnRequest.create(orderId, req.orderItemId(), ctx.shipmentId(), ctx.sellerId(),
                order.getMemberId(), req.type(), req.reason(), req.reasonCode(), ctx.quantity(), req.exchangeOptionId());
        returnRequestRepository.save(r);
        return ReturnResponse.from(r);
    }

    /** 셀러 처리(승인/거부/수거/검수/환불) — 자기 셀러 반품만(트랜잭션 내 소유권 검증). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnResponse advanceForSeller(Long returnId, Long sellerId, ReturnStatusUpdateRequest req, Long changedBy) {
        Locked locked = lockReturnUnderOrder(returnId);
        if (!locked.returnRequest().belongsToSeller(sellerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인 셀러의 반품만 처리할 수 있습니다.");
        }
        applyAction(locked, req.action(), changedBy, req.memo());
        return ReturnResponse.from(locked.returnRequest());
    }

    /** ADMIN 대행 — 지정 주문의 반품만(경로 불일치 404). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnResponse advanceForAdmin(Long orderId, Long returnId, ReturnStatusUpdateRequest req, Long changedBy) {
        Locked locked = lockReturnUnderOrder(returnId);
        if (!locked.returnRequest().getOrderId().equals(orderId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "해당 주문의 반품이 아닙니다.");
        }
        applyAction(locked, req.action(), changedBy, req.memo());
        return ReturnResponse.from(locked.returnRequest());
    }

    /** 부모 주문 → 반품 순으로 비관락(락 순서 일관 — 데드락 회피). 반품 전이가 부모 주문 취소/배송과 직렬화된다. */
    private Locked lockReturnUnderOrder(Long returnId) {
        Long orderId = returnRequestRepository.findOrderIdById(returnId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "반품 요청을 찾을 수 없습니다."));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
        ReturnRequest r = returnRequestRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "반품 요청을 찾을 수 없습니다."));
        return new Locked(order, r);
    }

    private void applyAction(Locked locked, ReturnAction action, Long changedBy, String memo) {
        ReturnRequest r = locked.returnRequest();
        switch (action) {
            case APPROVE -> r.approve(changedBy);
            case REJECT -> r.reject(changedBy, memo);
            case PICK_UP -> r.pickUp(changedBy);
            case INSPECT -> r.inspect(changedBy);
            case REFUND -> refund(locked.order(), r, changedBy);
            case COMPLETE -> exchange(locked.order(), r, changedBy);
        }
        // 전이 성공 후 구매자 알림 이벤트(#6 P2c) — 같은 트랜잭션이라 상태 변경과 원자적. 전이가 예외로 막히면 발행도 롤백.
        returnEventEmitter.emitStatusChanged(r);
    }

    /**
     * 반품 검수확정(INSPECTED→REFUNDED) — 환불 + OrderItem RETURNED flip + 재입고를 한 트랜잭션·부모 주문 락 아래서.
     *
     * <p>순서(리뷰 HIGH 교정): <b>flip·상태전이 검증을 PG 환불보다 먼저</b> — markReturned(ACTIVE 가드)·markRefunded
     * (INSPECTED·RETURN 가드)가 모두 통과한 뒤에야 PG 환불을 호출한다. 환불 실패 시 502로 전체 롤백(flip·재입고 무산).
     */
    private void refund(Order order, ReturnRequest r, Long changedBy) {
        if (r.getType() != com.commerce.api.returns.entity.ReturnType.RETURN) {
            throw new BusinessException(HttpStatus.CONFLICT, "반품(RETURN) 요청만 환불할 수 있습니다.");   // flip·PG 이전 조기 차단
        }
        OrderItem item = order.requireItem(r.getOrderItemId());
        long refundAmount = order.effectivePriceOf(item);
        item.markReturned();                                   // flip (ACTIVE 가드) — PG 이전
        r.markRefunded(refundAmount, r.isRestock(), changedBy);// INSPECTED→REFUNDED (RETURN·INSPECTED 가드) — PG 이전
        paymentService.refundForReturn(order.getId(), refundAmount);   // PG 환불 (외부 부작용)
        if (r.isRestock()) {
            stockReservationService.undoForOrderItem(r.getOrderItemId());   // 재입고(CONSUMED→실재고 복원). 검수확정 시점에만.
        }
    }

    /**
     * 교환 검수확정(INSPECTED→COMPLETED, EXCHANGE 전용) — <b>옵션 스왑 + 대체품 재출고</b>. 환불 없음·동일가라
     * revenue-neutral(원 OrderItem을 ACTIVE 유지해 getSubtotal 불변 → discountShares·정산 델타 0).
     *
     * <p>순서: 상태·타입 검증(부작용 전) → 대체품 소진(품절이면 409로 전체 롤백, 자동 환불 전환 금지) → 원품 복원 →
     * 옵션 스왑 → 교환 재출고 shipment(kind=EXCHANGE, rollup 제외라 DELIVERED 주문 후퇴 없음) → COMPLETED.
     */
    private void exchange(Order order, ReturnRequest r, Long changedBy) {
        if (r.getType() != com.commerce.api.returns.entity.ReturnType.EXCHANGE) {
            throw new BusinessException(HttpStatus.CONFLICT, "교환(EXCHANGE) 요청만 교환 확정할 수 있습니다.");
        }
        if (r.getStatus() != com.commerce.api.returns.entity.ReturnStatus.INSPECTED) {
            throw new BusinessException(HttpStatus.CONFLICT, "검수 완료 후에만 교환을 확정할 수 있습니다.");
        }
        OrderItem item = order.requireItem(r.getOrderItemId());
        Long oldOptionId = item.getOptionId();
        Long newOptionId = r.getExchangeOptionId();
        ProductOption newOption = productOptionRepository.findById(newOptionId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "교환 대상 옵션을 찾을 수 없습니다."));
        if (!newOption.getProduct().getId().equals(item.getProductId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "같은 상품의 다른 옵션으로만 교환할 수 있습니다.");
        }
        if (newOptionId.equals(oldOptionId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "같은 옵션으로는 교환할 수 없습니다.");
        }
        // 두 옵션 행을 id 오름차순으로 먼저 비관락 → 미러 교환(다른 주문이 같은 두 옵션을 반대 방향) 간 데드락 예방
        // (적대적리뷰 LOW). 부모 주문 락은 '같은 주문'만 직렬화하므로 공유 product_option 락 순서를 여기서 통일한다.
        productOptionRepository.findByIdForUpdate(Math.min(oldOptionId, newOptionId));
        productOptionRepository.findByIdForUpdate(Math.max(oldOptionId, newOptionId));
        // 대체품 소진 먼저(품절이면 409로 롤백 — 아직 원품·아이템 미변경). expiresAt은 CONSUMED라 무의미.
        stockReservationService.consumeForExchange(order.getId(), r.getOrderItemId(), newOptionId, item.getQuantity(),
                java.time.LocalDateTime.now().plusMinutes(30));
        stockReservationService.restoreOption(r.getOrderItemId(), oldOptionId, item.getQuantity());   // 반환된 원품 재입고
        item.swapOption(newOptionId, newOption.getSize());   // 원 항목 ACTIVE 유지·optionId/size만 교체(revenue-neutral)
        Shipment exchangeShipment = order.addExchangeShipment(item.getSellerId());
        orderRepository.saveAndFlush(order);   // cascade로 교환 shipment id 부여
        r.markExchanged(exchangeShipment.getId(), changedBy);
    }

    private record Locked(Order order, ReturnRequest returnRequest) {
    }
}
