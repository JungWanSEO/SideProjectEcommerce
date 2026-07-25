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
     * 이 회원이 해당 상품을 주어진 상태들 중 하나로 <b>ACTIVE 항목으로</b> 구매한 적이 있는지 — 리뷰 "구매자만 작성" 검증용.
     * {@link OrderStatus#PURCHASED}를 넘겨 결제 후 배송 중/완료까지 구매로 인정하되, 해당 상품 항목이
     * <b>ACTIVE(취소·반품되지 않음)</b>일 때만 자격을 준다(#3 교정 — 반품/취소한 상품엔 리뷰 불가). 항목 상태를
     * 조인 조건에 함께 걸어야 하므로 파생 쿼리 대신 JPQL로 명시한다(같은 조인의 동일 항목에 두 조건 적용).
     */
    @Query("""
            select case when count(oi) > 0 then true else false end
            from Order o join o.orderItems oi
            where o.memberId = :memberId
              and o.status in :statuses
              and oi.productId = :productId
              and oi.status = com.commerce.api.order.entity.OrderItemStatus.ACTIVE
            """)
    boolean hasActivePurchase(Long memberId, Collection<OrderStatus> statuses, Long productId);

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
     * shipment 없는 PURCHASED 주문의 <b>ID만</b> — 백필을 주문별 개별 트랜잭션으로 처리하기 위한 후보 목록(#1 리뷰 #4·#6 교정).
     * 대량 주문을 한 트랜잭션/힙에 다 올리지 않고, 주문마다 락+재확인 후 백필해 동시 취소와 직렬화하고 tx 크기를 제한한다.
     */
    @Query("select o.id from Order o where o.status in "
            + "(com.commerce.api.order.entity.OrderStatus.PAID, "
            + "com.commerce.api.order.entity.OrderStatus.SHIPPING, "
            + "com.commerce.api.order.entity.OrderStatus.DELIVERED) "
            + "and not exists (select 1 from Shipment s where s.order = o)")
    List<Long> findPurchasedWithoutShipmentIds();

    /**
     * 주문 상태/원장을 바꾸는 <b>모든 경로</b>가 부모 주문 행을 <b>비관적 쓰기 락</b>으로 먼저 잡는다(#1 리뷰 교정).
     * shipment 전진·취소(cancel/cancelItem)·ADMIN 일괄 전진·백필이 이 락으로 <b>부작용(PG 환불) 이전에</b> 직렬화된다.
     *
     * <p>왜 비관락인가: {@link Order#status}는 shipment rollup 파생값이고 rollup write는 <b>조건부</b>(값이 바뀔 때만)라,
     * 낙관락만으론 서로 다른 자식(shipment/항목)을 동시에 바꾸는 두 tx가 각자 형제를 stale로 읽어 둘 다 "변화 없음"으로
     * 판단→충돌 없이 커밋되는 lost update가 난다(리뷰 확정). 부모 행을 락으로 잡으면 늦은 tx가 <b>로드 시점에</b> 막혀
     * 앞 tx 커밋 후 형제의 최신 상태를 읽어 rollup을 정확히 재계산한다. 취소는 PG 환불 부작용이 있어 낙관락 재시도가
     * 부적합(재시도=이중 환불)하므로, 부작용 전에 직렬화하는 비관락이 정답이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}