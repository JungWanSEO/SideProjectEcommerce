package com.commerce.api.settlement.repository;

import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRepository extends JpaRepository<SettlementEntry, Long>, SettlementRepositoryCustom {

    /**
     * 해당 결제에 대한 정산 항목이 이미 있는지.
     * 정산 배치가 같은 결제를 두 번 잡지 않도록(멱등) 사용한다.
     * (예전엔 payment_id UNIQUE 제약이 중복을 막았으나, V24에서 역분개(같은 결제에 음수 상계 행 추가)를
     *  위해 제거했다. 이제 멱등은 단일 스레드 배치 + 이 체크가 보장하고, DB엔 조회용 비-UNIQUE 인덱스만 있다[V40].)
     */
    boolean existsByPaymentId(Long paymentId);

    /** 지급 묶음 생성 대상 — 셀러의 SCHEDULED·미지급(payoutId null) 항목 중 정산일이 기간 안인 것. */
    List<SettlementEntry> findBySellerIdAndStatusAndPayoutIdIsNullAndSettledDateBetween(
            Long sellerId, SettlementStatus status, LocalDate from, LocalDate to);

    /** 지급 묶음에 묶인 항목들(지급 처리 시 PAID_OUT 전이용). */
    List<SettlementEntry> findByPayoutId(Long payoutId);

    /** 한 결제의 모든 정산 항목(역분개 상계 diff 계산용 — 정방향 + 기존 역분개 합산). */
    List<SettlementEntry> findByPaymentId(Long paymentId);

    /**
     * 정산일 윈도우 안의 항목(대사용). from/to는 각각 null이면 그 방향 무제한
     * (ReconciliationService.inWindow와 동일 규칙: [from, to] 포함, settledDate null은 대사 대상 아님이라 제외).
     * 기존엔 findAll 후 Java에서 걸렀다 → 윈도우 대사가 전체를 읽던 것을 DB로 내려 좁힌다.
     */
    @Query("select s from SettlementEntry s where s.settledDate is not null "
            + "and (:from is null or s.settledDate >= :from) "
            + "and (:to is null or s.settledDate <= :to)")
    List<SettlementEntry> findBySettledDateWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 주어진 상태 정산 항목의 실수령(netAmount) 합 — 대시보드 "정산 대기 금액" KPI(SCHEDULED).
     * {@code coalesce(...,0)} 으로 항목이 없을 때 0 반환(primitive 언박싱 안전).
     */
    @Query("select coalesce(sum(s.netAmount), 0) from SettlementEntry s where s.status = :status")
    long sumNetAmountByStatus(@Param("status") SettlementStatus status);
}
