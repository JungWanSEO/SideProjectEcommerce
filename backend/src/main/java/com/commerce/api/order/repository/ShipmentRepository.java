package com.commerce.api.order.repository;

import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 배송(shipment) 리포지토리 — Order 애그리거트 내부지만, 셀러 스코프 전이·백필이 shipment를 직접
 * 주소지정(shipmentId)하므로 별도 리포지토리를 둔다.
 */
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    /** 한 주문의 shipment들(셀러별). 주문 상세·rollup·백필에서 쓴다. */
    List<Shipment> findByOrderId(Long orderId);

    /** 이 주문에 shipment가 이미 있는가 — 백필 per-order 멱등 판정(P2). */
    boolean existsByOrderId(Long orderId);

    /**
     * shipmentId → 소속 orderId만 스칼라로(엔티티 미로딩). 전이(P3)에서 주문을 락으로 다시 잡기 전에 쓴다 —
     * shipment 엔티티를 먼저 영속성 컨텍스트에 올리면 락 이후 형제 shipment를 stale로 읽을 수 있어(1차 캐시),
     * 스칼라만 뽑아 그 문제를 피한다.
     */
    @Query("select s.order.id from Shipment s where s.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    /**
     * 셀러의 배송 목록(셀러 콘솔 "출고 관리") — <b>자기 sellerId 것만</b>. status를 주면 그 상태만.
     *
     * <p>부모 주문을 <b>fetch join</b>한다: 응답({@code SellerShipmentResponse})이 주문의 항목·배송지를 읽으므로
     * shipment마다 주문을 다시 조회하면 N+1이 된다. 주문은 단일값 연관(ManyToOne)이라 fetch join과 페이지네이션을
     * 함께 써도 메모리 페이징으로 새지 않는다(컬렉션이었다면 문제였다). 항목 컬렉션은 배치 페치(100)로 묶인다.
     *
     * <p>스코프 강제는 이 쿼리 자체다 — 서비스가 로그인 셀러의 sellerId를 넣으므로 남의 배송은 <b>조회 자체가
     * 불가능</b>하다(전이 API가 소유권을 재검증하는 것과 이중 방어).
     */
    @Query(value = "select s from Shipment s join fetch s.order o "
            + "where s.sellerId = :sellerId and (:status is null or s.status = :status)",
            countQuery = "select count(s) from Shipment s "
                    + "where s.sellerId = :sellerId and (:status is null or s.status = :status)")
    Page<Shipment> findSellerShipments(@Param("sellerId") Long sellerId,
            @Param("status") ShipmentStatus status, Pageable pageable);
}
