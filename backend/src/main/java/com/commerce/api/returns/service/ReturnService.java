package com.commerce.api.returns.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.repository.OrderRepository;
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
        ReturnRequest r = ReturnRequest.create(orderId, req.orderItemId(), ctx.shipmentId(), ctx.sellerId(),
                memberId, req.type(), req.reason(), ctx.quantity(), req.exchangeOptionId());
        returnRequestRepository.save(r);
        return ReturnResponse.from(r);
    }

    /** 셀러 처리(승인/거부/수거/검수) — 자기 셀러 반품만(트랜잭션 내 소유권 검증). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnResponse advanceForSeller(Long returnId, Long sellerId, ReturnStatusUpdateRequest req, Long changedBy) {
        ReturnRequest r = lockReturnUnderOrder(returnId);
        if (!r.belongsToSeller(sellerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인 셀러의 반품만 처리할 수 있습니다.");
        }
        applyAction(r, req.action(), changedBy, req.memo());
        return ReturnResponse.from(r);
    }

    /** ADMIN 대행 — 지정 주문의 반품만(경로 불일치 404). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReturnResponse advanceForAdmin(Long orderId, Long returnId, ReturnStatusUpdateRequest req, Long changedBy) {
        ReturnRequest r = lockReturnUnderOrder(returnId);
        if (!r.getOrderId().equals(orderId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "해당 주문의 반품이 아닙니다.");
        }
        applyAction(r, req.action(), changedBy, req.memo());
        return ReturnResponse.from(r);
    }

    /** 부모 주문 → 반품 순으로 비관락(락 순서 일관 — 데드락 회피). 반품 전이가 부모 주문 취소/배송과 직렬화된다. */
    private ReturnRequest lockReturnUnderOrder(Long returnId) {
        Long orderId = returnRequestRepository.findOrderIdById(returnId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "반품 요청을 찾을 수 없습니다."));
        orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
        return returnRequestRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "반품 요청을 찾을 수 없습니다."));
    }

    private void applyAction(ReturnRequest r, ReturnAction action, Long changedBy, String memo) {
        switch (action) {
            case APPROVE -> r.approve(changedBy);
            case REJECT -> r.reject(changedBy, memo);
            case PICK_UP -> r.pickUp(changedBy);
            case INSPECT -> r.inspect(changedBy);
            case REFUND, COMPLETE -> throw new BusinessException(HttpStatus.CONFLICT,
                    "환불/교환 확정은 아직 처리할 수 없습니다.");   // P4(환불)·P6(교환)에서 돈/재고 경로로 배선
        }
    }
}
