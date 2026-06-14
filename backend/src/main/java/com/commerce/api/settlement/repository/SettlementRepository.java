package com.commerce.api.settlement.repository;

import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<SettlementEntry, Long>, SettlementRepositoryCustom {

    /**
     * 해당 결제에 대한 정산 항목이 이미 있는지.
     * 정산 배치가 같은 결제를 두 번 잡지 않도록(멱등) 사용한다.
     * (DB에도 payment_id UNIQUE 제약을 둬서 동시 실행에도 중복을 막는다.)
     */
    boolean existsByPaymentId(Long paymentId);

    /** 지급 묶음 생성 대상 — 셀러의 SCHEDULED·미지급(payoutId null) 항목 중 정산일이 기간 안인 것. */
    List<SettlementEntry> findBySellerIdAndStatusAndPayoutIdIsNullAndSettledDateBetween(
            Long sellerId, SettlementStatus status, LocalDate from, LocalDate to);

    /** 지급 묶음에 묶인 항목들(지급 처리 시 PAID_OUT 전이용). */
    List<SettlementEntry> findByPayoutId(Long payoutId);

    /** 한 결제의 모든 정산 항목(역분개 상계 diff 계산용 — 정방향 + 기존 역분개 합산). */
    List<SettlementEntry> findByPaymentId(Long paymentId);
}
