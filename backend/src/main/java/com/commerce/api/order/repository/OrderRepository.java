package com.commerce.api.order.repository;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 DB 접근.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 특정 회원의 주문을 페이지로 조회 (정렬·페이지 크기는 Pageable에 따름). */
    Page<Order> findByMemberId(Long memberId, Pageable pageable);

    /**
     * 여러 상태(구매 완료 집합 등)의 모든 주문 — 추천 배치가 구매 신호를 모을 때 사용.
     * {@link OrderStatus#PURCHASED}(PAID·SHIPPING·DELIVERED)를 넘겨 "배송돼도 구매"를 포함한다.
     */
    List<Order> findByStatusIn(Collection<OrderStatus> statuses);

    /** 특정 상태의 주문을 페이지로 조회 — 어드민 주문 관리 화면의 상태 필터에 사용. */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * 이 회원이 해당 상품을 주어진 상태들 중 하나로 주문한 적이 있는지 — 리뷰 "구매자만 작성" 검증용.
     * {@link OrderStatus#PURCHASED}를 넘겨 결제 후 배송 중/완료까지 구매로 인정한다.
     * 주문 항목(orderItems) 컬렉션을 조인해 productId를 본다(파생 쿼리의 _ 는 연관 경로 탐색 표시).
     */
    boolean existsByMemberIdAndStatusInAndOrderItems_ProductId(
            Long memberId, Collection<OrderStatus> statuses, Long productId);
}