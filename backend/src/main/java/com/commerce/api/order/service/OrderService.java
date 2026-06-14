package com.commerce.api.order.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.CheckoutRequest;
import com.commerce.api.order.dto.OrderCreateRequest;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
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
    private final ProductRepository productRepository;

    /**
     * 주문 생성. 동시 재고 차감으로 낙관적 락 충돌이 나면 최대 3회까지 (새 트랜잭션으로) 재시도.
     * 재고가 정말 부족하면 BusinessException(재시도 대상 아님)으로 실패한다.
     */
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
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
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public OrderResponse checkout(Long memberId, CheckoutRequest request) {
        return orderProcessor.checkout(memberId, request);
    }

    /**
     * 결제 확정(PENDING → PAID + 재고 차감). 동시 재고 차감으로 낙관적 락 충돌이 나면
     * create와 동일하게 최대 3회까지 (새 트랜잭션으로) 재시도한다.
     */
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
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
        return findOrder(orderId).getOrderItems().stream()
                .map(OrderResponse.OrderItemResponse::from)
                .toList();
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
     * 주문 취소: 상태를 CANCELLED로 바꾸고, 결제 완료(PAID)였던 주문이면 차감했던 재고를 복원한다.
     * (PENDING 주문은 재고가 차감된 적 없으므로 복원하지 않는다.) 본인 주문이거나 ADMIN일 때만 허용.
     */
    @Transactional
    public OrderResponse cancel(Long id, Long requesterId, boolean admin) {
        Order order = findOwnedOrder(id, requesterId, admin);
        boolean wasPaid = order.isPaid();   // 상태를 바꾸기 전에 결제 여부 확인
        order.cancel();   // 이미 취소된 주문이면 예외

        if (wasPaid) {
            // 결제 완료된 주문만 재고가 차감돼 있으므로 복원한다.
            for (OrderItem item : order.getOrderItems()) {
                productRepository.findByOptionId(item.getOptionId())
                        .ifPresent(product -> product.increaseStock(item.getOptionId(), item.getQuantity()));
            }
        }
        return OrderResponse.from(order);
    }

    private Order findOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    /**
     * 주문을 찾고, 요청자가 소유자이거나 ADMIN인지 검증한다.
     * 둘 다 아니면 403 — 인증은 됐지만 남의 주문에 접근하려는 경우(IDOR 방지).
     */
    private Order findOwnedOrder(Long id, Long requesterId, boolean admin) {
        Order order = findOrder(id);
        if (!admin && !order.getMemberId().equals(requesterId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인의 주문만 접근할 수 있습니다.");
        }
        return order;
    }
}
