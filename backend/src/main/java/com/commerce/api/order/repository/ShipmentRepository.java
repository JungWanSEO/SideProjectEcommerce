package com.commerce.api.order.repository;

import com.commerce.api.order.entity.Shipment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 배송(shipment) 리포지토리 — Order 애그리거트 내부지만, 셀러 스코프 전이·백필이 shipment를 직접
 * 주소지정(shipmentId)하므로 별도 리포지토리를 둔다. 주문 rollup 재계산은 P3에서 추가한다.
 */
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    /** 한 주문의 shipment들(셀러별). 주문 상세·rollup·백필에서 쓴다. */
    List<Shipment> findByOrderId(Long orderId);

    /** 이 주문에 shipment가 이미 있는가 — 백필 per-order 멱등 판정(P2). */
    boolean existsByOrderId(Long orderId);
}
