package com.commerce.api.order.repository;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 DB 접근.
 */
public interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {

    /** 특정 회원의 주문을 페이지로 조회 (정렬·페이지 크기는 Pageable에 따름). */
    Page<Order> findByMemberId(Long memberId, Pageable pageable);

    /** 멱등키로 주문 조회 — 체크아웃 중복 제출 판정(같은 키면 새로 만들지 않고 기존 주문을 돌려준다). */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /** 특정 상태 + 지정 시각 이전 생성된 주문 — 결제 대기(PENDING) 만료 배치가 대상을 고를 때 사용. */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime createdAt);

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
    //   매출 KPI·추이는 주문 gross(totalPrice−discount)가 부분취소를 과다계상해 결제·정산 net과 어긋나므로,
    //   PaymentRepository의 순매출(amount−refundedAmount) 쿼리로 옮겼다(#9). 여기엔 상태 분포만 남는다.

    /** 상태별 주문 수 — [status, count] 행 목록. 서비스가 모든 enum 값으로 0 채워 분포를 만든다. */
    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countGroupByStatus();

    /**
     * shipment가 아직 없는 PURCHASED(PAID/SHIPPING/DELIVERED) 주문 — #1 P2 백필 대상.
     * per-order 멱등: 이미 shipment가 있는 주문(P2 이후 결제분)은 제외해 재실행에 안전하다.
     */
    @Query("select o from Order o where o.status in "
            + "(com.commerce.api.order.entity.OrderStatus.PAID, "
            + "com.commerce.api.order.entity.OrderStatus.SHIPPING, "
            + "com.commerce.api.order.entity.OrderStatus.DELIVERED) "
            + "and not exists (select 1 from Shipment s where s.order = o)")
    List<Order> findPurchasedWithoutShipments();

    /**
     * shipment 전이 시 부모 주문을 <b>낙관 버전 강제 증가</b>로 잡는다(#1 P3). 같은 주문의 서로 다른 shipment를
     * 동시에 전진하면 둘 다 orders.version을 올리려다 충돌 → 늦은 쪽이 @Retryable로 재시도해 fresh 컨텍스트에서
     * rollup을 다시 계산한다. (rollup write가 조건부라 낙관락만으론 stale sibling lost update가 나므로 강제 증가로 직렬화.)
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForShipmentRollup(@Param("id") Long id);
}