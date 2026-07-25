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

    /**
     * 이 주문 항목의 예약 전부(상태 무관) — 항목 취소 시 예약 상태로 실재고 복원(CONSUMED) vs 예약 해제(ACTIVE)를
     * 가르는 데 쓴다(#1 P4). 전체 Order.status가 아니라 항목별 실차감 여부로 판정해 멀티셀러 재고 누락을 막는다.
     */
    List<StockReservation> findByOrderItemId(Long orderItemId);

    /** 이 주문 항목의 특정 옵션 예약 — 교환(#3 P6)에서 원 옵션만 골라 복원할 때(옵션 스왑 후 이중복원 회피). */
    List<StockReservation> findByOrderItemIdAndOptionId(Long orderItemId, Long optionId);
}
