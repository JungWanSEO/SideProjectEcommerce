package com.commerce.api.product.repository;

import com.commerce.api.product.entity.StockReservation;
import com.commerce.api.product.entity.StockReservationStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재고 예약 조회 — 주문 생명주기(결제·취소·만료)에서 그 주문의 ACTIVE 예약을 찾아 소진/해제한다.
 */
public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    /** 이 주문의 특정 상태 예약들(보통 ACTIVE — 결제 소진·전체취소·만료 해제 대상). */
    List<StockReservation> findByOrderIdAndStatus(Long orderId, StockReservationStatus status);

    /** 이 주문 항목의 특정 상태 예약들(항목 단위 부분취소 시 그 항목 예약만 정확히 해제). */
    List<StockReservation> findByOrderItemIdAndStatus(Long orderItemId, StockReservationStatus status);
}
