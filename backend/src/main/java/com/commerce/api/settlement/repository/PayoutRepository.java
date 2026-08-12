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

    /**
     * 그 셀러의 <b>가장 최근</b> 지급 묶음(#8 후속 P6) — 이월 잔액(carriedOver)을 이어받을 때 읽는다.
     *
     * <p>기간(periodTo)이 아니라 <b>id</b> 내림차순인 이유: 이월은 "직전에 정산한 것"에서 이어받아야 하고,
     * 기간을 겹치거나 거꾸로 잡아 만든 묶음이 있어도 <b>실제로 마지막에 만든 것</b>이 현재 잔액이다.
     * (기간 기준으로 고르면 과거 기간을 뒤늦게 정산했을 때 이월을 두 번 태울 수 있다.)
     */
    java.util.Optional<Payout> findFirstBySellerIdOrderByIdDesc(Long sellerId);
}
