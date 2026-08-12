package com.commerce.api.settlement.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 셀러 지급 묶음(Payout) — 한 셀러에게 정산주기(기간)별로 한 번에 지급하는 단위.
 *
 * <p>정산 항목(SettlementEntry, 결제×셀러)이 "얼마를 정산하나"라면, Payout은 그것들을 셀러·기간으로
 * 묶어 "실제로 한 번에 얼마를 송금하나"를 1급으로 모델링한다(헤더-디테일: Payout 1 : N SettlementEntry).
 * 합계는 묶을 때 스냅샷하고, 지급(PAID) 시점에 묶인 항목들을 PAID_OUT으로 만든다.
 */
@Getter
@Entity
@Table(name = "payout")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;          // 지급 대상 셀러 (ID 참조)

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;   // 정산(입금)일 기준 기간 시작

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;     // 기간 끝

    @Column(name = "total_gross", nullable = false)
    private long totalGross;        // 매출 합계

    @Column(name = "total_fee", nullable = false)
    private long totalFee;          // PG 수수료 합계

    @Column(name = "total_platform_fee", nullable = false)
    private long totalPlatformFee;  // 플랫폼 수수료 합계

    @Column(name = "total_net", nullable = false)
    private long totalNet;          // 실지급액 합계 = max(0, 기간 net + carriedIn). 음수 송금은 만들지 않는다.

    /** 직전 기간에서 넘어온 잔액(≤0, #8 후속 P6). 이번 지급액에서 선차감된다. */
    @Column(name = "carried_in", nullable = false)
    private long carriedIn;

    /** 이번 기간에서 다음으로 넘기는 잔액(≤0). 부족분만 넘어간다. */
    @Column(name = "carried_over", nullable = false)
    private long carriedOver;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;         // 묶인 정산 항목 수

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;   // 지급 완료 시각 (PENDING이면 null)

    private Payout(Long sellerId, LocalDate periodFrom, LocalDate periodTo, long totalGross, long totalFee,
                   long totalPlatformFee, long periodNet, long carriedIn, int entryCount) {
        this.sellerId = sellerId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.totalGross = totalGross;
        this.totalFee = totalFee;
        this.totalPlatformFee = totalPlatformFee;
        this.carriedIn = carriedIn;
        // 이월 정산(#8 후속 P6): settleable = 이번 기간 net + 직전 이월(≤0).
        //   지급액은 그중 양수분만(음수 송금은 만들지 않는다), 부족분은 다음 기간으로 넘긴다.
        //   기간 엔트리는 지급액이 0이어도 이 묶음에 전부 편입된다 — 항목이 정확히 한 번만 소비되고
        //   "이 기간은 0원 정산, N원 이월"이라는 기록이 남아 지급 이력이 끊기지 않는다.
        long settleable = periodNet + carriedIn;
        this.totalNet = Math.max(0L, settleable);
        this.carriedOver = Math.min(0L, settleable);
        this.entryCount = entryCount;
        this.status = PayoutStatus.PENDING;
    }

    /**
     * 지급 묶음 생성(#8 후속 P6) — 이번 기간 net과 직전 이월을 받아 지급액·다음 이월을 <b>엔티티가 파생</b>한다.
     *
     * @param periodNet 이번 기간 정산 항목 net 합(음수일 수 있다)
     * @param carriedIn 직전 기간에서 넘어온 잔액(≤0, 없으면 0)
     */
    public static Payout create(Long sellerId, LocalDate periodFrom, LocalDate periodTo,
            long totalGross, long totalFee, long totalPlatformFee, long periodNet, long carriedIn, int entryCount) {
        if (carriedIn > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "이월 잔액은 0 이하여야 합니다.");
        }
        return new Payout(sellerId, periodFrom, periodTo, totalGross, totalFee, totalPlatformFee,
                periodNet, carriedIn, entryCount);
    }

    /** 이월 없는 생성(기존 호출부 호환) — 직전 이월 0으로 위임. */
    public static Payout create(Long sellerId, LocalDate periodFrom, LocalDate periodTo,
            long totalGross, long totalFee, long totalPlatformFee, long periodNet, int entryCount) {
        return create(sellerId, periodFrom, periodTo, totalGross, totalFee, totalPlatformFee, periodNet, 0L, entryCount);
    }

    /** 지급 완료 처리 — 이미 지급됐으면 409. */
    public void markPaid() {
        if (this.status == PayoutStatus.PAID) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 지급된 묶음입니다.");
        }
        this.status = PayoutStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }
}
