package com.commerce.api.order.repository;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // === 어드민 대시보드 집계 (읽기 전용) =========================================

    /**
     * 주어진 상태들의 결제액(= totalPrice - discountAmount) 합. 대시보드 "결제완료 매출" KPI.
     * {@code coalesce(...,0)} 으로 대상이 없을 때 null 대신 0을 돌려 primitive 언박싱 안전.
     */
    @Query("select coalesce(sum(o.totalPrice - o.discountAmount), 0) from Order o where o.status in :statuses")
    long sumPayableAmountByStatusIn(@Param("statuses") Collection<OrderStatus> statuses);

    /** 상태별 주문 수 — [status, count] 행 목록. 서비스가 모든 enum 값으로 0 채워 분포를 만든다. */
    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countGroupByStatus();

    /**
     * 기간 시작 이후(>=) 해당 상태들 주문의 [createdAt, 결제액] 행 — 일별 매출 추이용.
     * 일자 그룹핑은 H2/MySQL 날짜함수 차이를 피해 <b>서비스(자바)에서</b> 한다.
     */
    @Query("select o.createdAt, (o.totalPrice - o.discountAmount) from Order o "
            + "where o.status in :statuses and o.createdAt >= :from")
    List<Object[]> findAmountsByStatusInSince(
            @Param("statuses") Collection<OrderStatus> statuses, @Param("from") LocalDateTime from);
}