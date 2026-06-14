package com.commerce.api.settlement.repository;

import com.commerce.api.settlement.entity.Payout;
import com.commerce.api.settlement.entity.PayoutStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 지급 묶음(Payout) DB 접근. 셀러·상태 필터(파생 쿼리, ReconciliationService 필터 패턴).
 */
public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Page<Payout> findBySellerId(Long sellerId, Pageable pageable);

    Page<Payout> findByStatus(PayoutStatus status, Pageable pageable);

    Page<Payout> findBySellerIdAndStatus(Long sellerId, PayoutStatus status, Pageable pageable);
}
