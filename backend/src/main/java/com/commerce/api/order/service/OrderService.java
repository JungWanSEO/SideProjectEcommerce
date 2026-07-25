package com.commerce.api.order.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.CheckoutRequest;
import com.commerce.api.order.dto.CouponPreviewResponse;
import com.commerce.api.order.dto.OrderCreateRequest;
import com.commerce.api.order.dto.OrderDiscountInfo;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.dto.OrderSearchCondition;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.service.StockReservationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 비즈니스 로직 (조회/취소 + 생성 재시도 오케스트레이션).
 *
 * 생성은 OrderProcessor.place(@Transactional)에 위임하고 @Retryable로 감싼다.
 * → 낙관적 락 충돌(재고 동시 차감) 시 트랜잭션을 새로 시작해 재시도한다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderProcessor orderProcessor;
    private final OrderRepository orderRepository;
    private final StockReservationService stockReservationService;   // 예약 해제·항목별 재고 되돌리기(#2·#1)

    /**
     * 주문 생성. 동시 재고 차감으로 낙관적 락 충돌이 나면 최대 3회까지 (새 트랜잭션으로) 재시도.
     * 재고가 정말 부족하면 BusinessException(재시도 대상 아님)으로 실패한다.
     */
    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public OrderResponse create(Long memberId, OrderCreateRequest request) {
        return orderProcessor.place(memberId, request);
    }

    /**
     * 체크아웃: 장바구니를 주문으로 만들고 장바구니를 비운다(서버 트랜잭션). create와 동일하게
     * 낙관적 락 충돌 시 새 트랜잭션으로 재시도. 빈 장바구니면 400.
     */
    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public OrderResponse checkout(Long memberId, CheckoutRequest request) {
        try {
            return orderProcessor.checkout(memberId, request);
        } catch (DataIntegrityViolationException e) {
            // 동시 중복 제출(더블클릭): 두 요청이 모두 "기존 주문 없음"을 보고 각자 INSERT → 멱등키 UNIQUE 위반.
            //   진 쪽 트랜잭션은 롤백됐고 이긴 쪽 주문은 커밋돼 있으므로, 그 주문을 찾아 같은 응답을 돌려준다
            //   (사용자에겐 "한 번 눌린 것"으로 보인다). 트랜잭션이 이미 롤백돼 조회는 새 트랜잭션에서 — 그래서 프록시 경유.
            return orderProcessor.findByIdempotencyKey(memberId, request.idempotencyKey())
                    .orElseThrow(() -> e);   // 멱등키와 무관한 무결성 위반이면 원래 예외를 그대로

        }
    }

    /** 쿠폰 미리보기(주문 생성 없음) — 현재 장바구니 기준 할인·예상 결제액. 읽기 전용이라 재시도 불필요. */
    public CouponPreviewResponse previewCoupon(Long memberId, String couponCode) {
        return orderProcessor.previewCoupon(memberId, couponCode);
    }

    /**
     * 결제 확정(PENDING → PAID + 재고 차감). 동시 재고 차감으로 낙관적 락 충돌이 나면
     * create와 동일하게 최대 3회까지 (새 트랜잭션으로) 재시도한다.
     */
    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public OrderResponse pay(Long orderId) {
        return orderProcessor.pay(orderId);
    }

    /** 단건 조회 — 본인 주문이거나 ADMIN일 때만 허용. */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id, Long requesterId, boolean admin) {
        return OrderResponse.from(findOwnedOrder(id, requesterId, admin));
    }

    /**
     * 주문 항목 목록(셀러·소계 포함) — 정산 도메인이 셀러별로 매출을 분해할 때 읽는다.
     * 소유권 검증 없음(ADMIN 정산 배치 전용). settlement → order 의존은 이 메서드 + DTO로만(경계 유지).
     */
    @Transactional(readOnly = true)
    public List<OrderResponse.OrderItemResponse> getOrderItems(Long orderId) {
        Order order = findOrder(orderId);
        var shares = order.discountShares();   // 항목별 안분 할인 — 정산이 활성 항목 실효가를 쓰게 함
        return order.getOrderItems().stream()
                .map(item -> OrderResponse.OrderItemResponse.from(item, shares.getOrDefault(item, 0L)))
                .toList();
    }

    /**
     * 주문의 쿠폰 할인 스냅샷(할인액·부담주체·셀러 귀속) — 정산이 할인을 셀러/플랫폼으로 분담할 때 읽는다.
     * getOrderItems와 같은 settlement → order 경계 경로(서비스 + DTO). 소유권 검증 없음(ADMIN 배치 전용).
     */
    @Transactional(readOnly = true)
    public OrderDiscountInfo getOrderDiscount(Long orderId) {
        return OrderDiscountInfo.from(findOrder(orderId));
    }

    /**
     * 내 주문 목록 (페이지). memberId로 필터하므로 본인 주문만 조회된다.
     * 항목(orderItems)은 지연 로딩이지만 default_batch_fetch_size로 IN 조회 묶음 → N+1 완화.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getMyOrders(Long memberId, Pageable pageable) {
        return PageResponse.from(
                orderRepository.findByMemberId(memberId, pageable)
                        .map(OrderSummaryResponse::from));   // 목록은 요약(상세는 getOrder의 OrderResponse)
    }

    /**
     * 주문 검색(ADMIN) — 수령인·주문번호·회원·상태·기간·금액으로 동적 검색(QueryDSL).
     * 회원 스코핑 없이 모든 주문을 본다(어드민 운영 화면 전용 — 인가는 SecurityConfig·컨트롤러가 보장).
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> searchOrders(OrderSearchCondition condition, Pageable pageable) {
        return PageResponse.from(
                orderRepository.search(condition, pageable).map(OrderSummaryResponse::from));
    }

    /**
     * 셀러 콘솔 "내 주문" — 이 셀러의 상품이 든 주문만. 어드민 검색과 같은 쿼리에 sellerId를 강제로 채운다.
     * (요청이 sellerId를 보내더라도 로그인 셀러의 것으로 덮어써 남의 셀러 주문을 볼 수 없게 한다.)
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> searchSellerOrders(
            Long sellerId, OrderSearchCondition condition, Pageable pageable) {
        OrderSearchCondition scoped = new OrderSearchCondition(
                condition.keyword(), condition.memberId(), condition.status(),
                condition.from(), condition.to(), condition.minAmount(), condition.maxAmount(),
                sellerId);   // 셀러 스코프 고정(요청값 무시)
        return PageResponse.from(
                orderRepository.search(scoped, pageable).map(OrderSummaryResponse::from));
    }

    /**
     * 배송 상태 전진(ADMIN): PAID → SHIPPING → DELIVERED (forward-only). 없으면 404,
     * 잘못된 전이(건너뛰기·되돌리기·취소/대기 상태)면 409(Order.advanceShipping이 강제).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse advanceShipping(Long id, OrderStatus next, Long changedBy,
            String courier, String trackingNumber) {
        // 부모 주문을 비관적 락으로 — shipment 단위 전진(worker)·동시 취소와 같은 락으로 직렬화(#1 리뷰 교정).
        Order order = findOrderForUpdate(id);
        order.advanceShipping(next, changedBy, courier, trackingNumber);   // shipment 일괄 전진 + rollup
        return OrderResponse.from(order);
    }

    /**
     * 주문 취소(#1 c안): 아직 출고 전인 활성 항목을 취소하고, <b>취소된 각 항목의 재고를 항목별로 되돌린다</b>
     * (예약 상태로 판정 — CONSUMED면 실재고 복원, ACTIVE면 예약 해제). 출고 시작된 셀러 항목은 남는다(부분 취소).
     * 본인 주문이거나 ADMIN일 때만 허용. 취소 가능한 항목이 하나도 없으면 409.
     */
    @Transactional
    public OrderResponse cancel(Long id, Long requesterId, boolean admin) {
        // 부모 주문 비관적 락 — shipment 전진·동시 취소와 직렬화(#1 리뷰 교정, PG 환불 이전에). 격리는 호출자
        //   PaymentService.cancelOrder(READ_COMMITTED)가 정한다 → 락 이후 형제 shipment를 fresh로 읽는다.
        Order order = findOwnedOrderForUpdate(id, requesterId, admin);
        // 취소된 항목만 정확히 되돌린다(이미 취소됐거나 출고된 항목은 대상 아님 — 이중 복원 방지).
        for (OrderItem item : order.cancel(requesterId, admin ? "관리자 취소" : "주문자 취소")) {
            stockReservationService.undoForOrderItem(item.getId());
        }
        return OrderResponse.from(order);
    }

    /**
     * 주문 항목 단위 취소(부분환불의 주문/재고 부분). 본인 주문이거나 ADMIN만.
     * 항목을 CANCELLED로 만들고, <b>그 항목의 재고를 예약 상태로 되돌린다</b>(CONSUMED→실재고 복원 / ACTIVE→예약 해제).
     * 전체 Order.status가 아니라 항목별 실차감 여부로 판정해, 멀티셀러에서 다른 셀러가 출고해 주문이 PAID를 벗어나도
     * 이 항목 재고가 정확히 돌아온다. (PG 부분 환불은 호출자=PaymentService가 이어서 처리 — 순환 의존 회피.)
     */
    @Transactional
    public OrderResponse cancelItem(Long orderId, Long orderItemId, Long requesterId, boolean admin) {
        // 부모 주문 비관적 락(#1 리뷰 교정) — 동시 항목취소/전진과 직렬화. 이 락이 결제 원장 읽기·갱신도 보호해
        //   Payment.refundedAmount lost update(리뷰 #2)를 함께 막는다(PaymentService가 이 락을 잡은 뒤 결제를 만진다).
        Order order = findOwnedOrderForUpdate(orderId, requesterId, admin);
        OrderItem cancelled = order.cancelItem(orderItemId, requesterId);   // shipment-grain 취소 가능 판정 + rollup
        stockReservationService.undoForOrderItem(cancelled.getId());
        return OrderResponse.from(order);
    }

    private Order findOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    /** 주문을 <b>비관적 쓰기 락</b>으로 찾는다 — 상태/원장 변경 경로가 부모 주문에서 직렬화되도록(#1 리뷰 교정). */
    private Order findOrderForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    /**
     * 주문을 찾고, 요청자가 소유자이거나 ADMIN인지 검증한다.
     * 둘 다 아니면 403 — 인증은 됐지만 남의 주문에 접근하려는 경우(IDOR 방지).
     */
    private Order findOwnedOrder(Long id, Long requesterId, boolean admin) {
        return ensureOwner(findOrder(id), requesterId, admin);
    }

    /** 소유권 검증 + 비관적 쓰기 락(취소 경로 — 상태 변경 직렬화). */
    private Order findOwnedOrderForUpdate(Long id, Long requesterId, boolean admin) {
        return ensureOwner(findOrderForUpdate(id), requesterId, admin);
    }

    private Order ensureOwner(Order order, Long requesterId, boolean admin) {
        if (!admin && !order.getMemberId().equals(requesterId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인의 주문만 접근할 수 있습니다.");
        }
        return order;
    }
}
