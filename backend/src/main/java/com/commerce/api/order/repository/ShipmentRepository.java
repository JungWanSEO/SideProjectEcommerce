package com.commerce.api.order.repository;

import com.commerce.api.order.entity.Shipment;
import java.util.List;
import java.util.Optional;
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
}
