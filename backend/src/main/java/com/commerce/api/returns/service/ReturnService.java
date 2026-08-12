package com.commerce.api.returns.service;

import com.commerce.api.coupon.service.MemberCouponService;
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
    private final MemberCouponService memberCouponService;   // 전량 이탈 시 쿠폰 복원 — returns→coupon 단방향
    private final com.commerce.api.order.service.ShippingPolicy shippingPolicy;   // 회수비 요율·부담 매트릭스(#8 후속)

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
        // 교환이면 대체 옵션을 신청 시점에 검증(오너 결정) — 같은 상품·다른 옵션·가용재고. 예전엔 확정(COMPLETE)
        // 시점에만 봤기 때문에, 구매자가 품절 사이즈로 신청 → 수거·검수까지 다 끝난 뒤에야 409로 막히는 헛수고가
        // 났다. 여기서 막으면 신청 화면에서 즉시 알 수 있다. (재고를 잡아두지는 않는다 — 확정 시점의 원자적
        // consumeForExchange가 여전히 진짜 게이트다. 여기 검사는 "명백한 품절 조기 차단"이지 예약이 아니다.)
        if (req.type() == com.commerce.api.returns.entity.ReturnType.EXCHANGE && req.exchangeOptionId() != null) {
            OrderItem target = order.requireItem(req.orderItemId());
            ProductOption option = requireExchangeOption(target, req.exchangeOptionId());
            if (option.available() < target.getQuantity()) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "선택한 사이즈의 재고가 부족합니다. (가용 " + Math.max(option.available(), 0) + "개)");
            }
        }
        // 소유권은 실제 구매자(order.memberId)로 귀속 — ADMIN 대행(admin=true) 생성 시에도 구매자가 자기 반품을
        // /returns/me로 조회·추적할 수 있게 한다(적대적리뷰 LOW: caller(admin) id로 귀속되던 문제 교정).
        ReturnRequest r = ReturnRequest.create(orderId, req.orderItemId(), ctx.shipmentId(), ctx.sellerId(),
                order.getMemberId(), req.type(), req.reason(), req.reasonCode(), ctx.quantity(), req.exchangeOptionId());
        // 회수비 요율을 신청 시점에 스냅샷(#8 후속) — 정책값이 올라도 진행 중인 반품의 부담액은 안 바뀐다
        // (고객이 신청 화면에서 고지받은 금액 = 실제 차감액). 교환은 환불이 없어 부과 대상이 아니므로 0.
        r.snapshotReturnShippingFee(
                req.type() == com.commerce.api.returns.entity.ReturnType.RETURN ? shippingPolicy.getReturnFee() : 0L);
        returnRequestRepository.save(r);
        returnEventEmitter.emitStatusChanged(r);   // REQUESTED → 셀러에게 "반품 요청 접수" 알림(#6 P3b·같은 tx라 원자적)
        return ReturnResponse.from(r);
    }

    /** 셀러 처리(승인/거부/수거/검수/환불) — 자기 셀러 반품만(트랜잭션 내 소유권 검증). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnResponse advanceForSeller(Long returnId, Long sellerId, ReturnStatusUpdateRequest req, Long changedBy) {
        Locked locked = lockReturnUnderOrder(returnId);
        if (!locked.returnRequest().belongsToSeller(sellerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인 셀러의 반품만 처리할 수 있습니다.");
        }
        applyAction(locked, req, changedBy, false);   // 셀러는 검수(INSPECT)에서만 귀책을 정한다 — 재정은 ADMIN 몫
        return ReturnResponse.from(locked.returnRequest());
    }

    /** ADMIN 대행 — 지정 주문의 반품만(경로 불일치 404). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnResponse advanceForAdmin(Long orderId, Long returnId, ReturnStatusUpdateRequest req, Long changedBy) {
        Locked locked = lockReturnUnderOrder(returnId);
        if (!locked.returnRequest().getOrderId().equals(orderId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "해당 주문의 반품이 아닙니다.");
        }
        applyAction(locked, req, changedBy, true);   // ADMIN은 종료 전이면 귀책 재정 가능
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

    /**
     * 액션 적용. {@code canOverrideFault}(ADMIN)이면 귀책 재정을 <b>액션보다 먼저</b> 반영한다 — 같은 요청에
     * REFUND가 함께 실려 와도 새 귀책으로 돈이 계산되어야 하기 때문이다(순서가 뒤바뀌면 옛 귀책으로 환불된다).
     */
    private void applyAction(Locked locked, ReturnStatusUpdateRequest req, Long changedBy, boolean canOverrideFault) {
        ReturnRequest r = locked.returnRequest();
        ReturnAction action = req.action();
        String memo = req.memo();
        if (action == ReturnAction.SET_FAULT && !canOverrideFault) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "귀책 재정은 관리자만 할 수 있습니다.");
        }
        if (canOverrideFault && req.faultParty() != null && action != ReturnAction.INSPECT) {
            r.overrideFault(req.faultParty(), changedBy);
        }
        switch (action) {
            case APPROVE -> r.approve(changedBy);
            case REJECT -> r.reject(changedBy, memo);
            case PICK_UP -> r.pickUp(changedBy);
            case INSPECT -> r.inspect(changedBy, req.faultParty());
            case REFUND -> refund(locked.order(), r, changedBy);
            case COMPLETE -> exchange(locked.order(), r, changedBy);
            case SET_FAULT -> requireFaultGiven(req);   // 재정은 위에서 이미 적용됨 — 여기선 누락만 막는다
        }
        // 전이 성공 후 구매자 알림 이벤트(#6 P2c) — 같은 트랜잭션이라 상태 변경과 원자적. 전이가 예외로 막히면 발행도 롤백.
        // SET_FAULT는 상태가 그대로라 제외한다 — 안 그러면 구매자에게 직전 상태 알림이 한 번 더 가는 중복이 된다.
        if (action != ReturnAction.SET_FAULT) {
            returnEventEmitter.emitStatusChanged(r);
        }
    }

    private void requireFaultGiven(ReturnStatusUpdateRequest req) {
        if (req.faultParty() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "재정할 귀책 주체(faultParty)가 필요합니다.");
        }
    }

    /**
     * 반품 검수확정(INSPECTED→REFUNDED) — 환불 + OrderItem RETURNED flip + 재입고를 한 트랜잭션·부모 주문 락 아래서.
     *
     * <p>순서(리뷰 HIGH 교정): <b>flip·상태전이 검증을 PG 환불보다 먼저</b> — markReturned(ACTIVE 가드)·markRefunded
     * (INSPECTED·RETURN 가드)가 모두 통과한 뒤에야 PG 환불을 호출한다. 환불 실패 시 502로 전체 롤백(flip·재입고 무산).
     * 회수비 산출·클램프도 <b>PG 이전</b>에 끝낸다(#8 후속) — 같은 이유로, 외부 부작용 뒤에 롤백 가능한 계산이
     * 오면 안 된다.
     *
     * <p><b>회수비(#8 후속)</b>: 고객 귀책이면 실지급액을 회수비만큼 줄이고, 줄인 만큼을 주문 누계에 더한다.
     * 더하지 않으면 "결제액 − 환불누계 == payable" 항등식이 깨져 이후 취소가 회수비를 자동 환급한다.
     * 실효가보다 크게 물릴 수 없도록 <b>클램프</b>한다 — 안 하면 저가·고할인 라인에서 음수 환불이 되어
     * {@code refundForReturn}의 {@code <=0} 조기 리턴에 걸리거나(무음 손실) Payment가 400을 던져 전체 롤백된다.
     */
    private void refund(Order order, ReturnRequest r, Long changedBy) {
        if (r.getType() != com.commerce.api.returns.entity.ReturnType.RETURN) {
            throw new BusinessException(HttpStatus.CONFLICT, "반품(RETURN) 요청만 환불할 수 있습니다.");   // flip·PG 이전 조기 차단
        }
        OrderItem item = order.requireItem(r.getOrderItemId());
        long gross = order.effectivePriceOf(item);
        long charge = Math.min(customerCharge(r), gross);      // 클램프 — 실효가보다 크게 물리지 않는다
        long payout = gross - charge;
        item.markReturned();                                   // flip (ACTIVE 가드) — PG 이전
        r.markRefunded(payout, charge, r.isRestock(), changedBy); // INSPECTED→REFUNDED (가드) — PG 이전
        order.addReturnShippingCharge(charge);                 // payable 가산 → 항등식 유지(이후 취소가 회수비를 환급하지 않게)
        paymentService.refundForReturn(order.getId(), payout);  // PG 환불 (외부 부작용)
        if (r.isRestock()) {
            stockReservationService.undoForOrderItem(r.getOrderItemId());   // 재입고(CONSUMED→실재고 복원). 검수확정 시점에만.
        }
        releaseCouponIfFullyWithdrawn(order);
    }

    /**
     * 고객이 부담할 회수비 — <b>확정 귀책</b>(P1 스냅샷)과 <b>신청 시점 요율 스냅샷</b>으로만 계산한다.
     * 둘 다 스냅샷이라 정책·매핑이 바뀌어도 진행 중이던 반품의 부담액은 흔들리지 않는다.
     * 레거시(요율 스냅샷 없음)는 0 — 소급 부과하지 않는다.
     */
    private long customerCharge(ReturnRequest r) {
        Long rate = r.getReturnShippingFee();
        return rate == null ? 0L : shippingPolicy.customerChargeOf(rate, r.effectiveFault());
    }

    /**
     * 전량 이탈 시 발급형 쿠폰 복원(오너 결정) — 취소분·반품분을 합쳐 <b>활성 항목이 0</b>이 되면 쿠폰을 돌려준다.
     *
     * <p>기존 복원 조건은 "주문 status == CANCELLED"뿐이라, 반품으로 전 항목이 빠져나간 주문은 원배송이
     * DELIVERED로 남아 <b>순수 전량취소와 대우가 달랐다</b>(같은 전액 환불인데 쿠폰만 소멸). 판정을
     * {@link Order#hasActiveItems()}로 바꿔 두 경로를 통일한다. 부분 반품은 활성이 남아 복원되지 않으므로
     * "싼 항목만 반품하고 쿠폰 재사용" 어뷰징 여지는 없다. release는 멱등이라 취소 경로와 겹쳐도 안전하다.
     */
    private void releaseCouponIfFullyWithdrawn(Order order) {
        if (!order.hasActiveItems()) {
            memberCouponService.release(order.getMemberId(), order.getCouponCode());
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
        ProductOption newOption = requireExchangeOption(item, newOptionId);
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

    /**
     * 교환 대체 옵션의 <b>형태</b> 검증 — 존재·같은 상품·현재와 다른 옵션. 신청(create)과 확정(exchange)이 같은
     * 규칙을 쓰도록 한 곳에 모은다(둘이 갈리면 "신청은 됐는데 확정이 안 되는" 요청이 생긴다).
     * 재고는 여기서 보지 않는다 — 신청은 스냅샷 검사(available), 확정은 원자적 소진이라 게이트가 다르다.
     */
    private ProductOption requireExchangeOption(OrderItem item, Long newOptionId) {
        ProductOption newOption = productOptionRepository.findById(newOptionId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "교환 대상 옵션을 찾을 수 없습니다."));
        if (!newOption.getProduct().getId().equals(item.getProductId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "같은 상품의 다른 옵션으로만 교환할 수 있습니다.");
        }
        if (newOptionId.equals(item.getOptionId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "같은 옵션으로는 교환할 수 없습니다.");
        }
        return newOption;
    }

    private record Locked(Order order, ReturnRequest returnRequest) {
    }
}
