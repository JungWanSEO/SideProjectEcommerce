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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 정산 항목 (애그리거트 루트).
 *
 * <p>"결제 한 건이 며칠 뒤 수수료를 떼고 얼마가 입금되는가"를 1급으로 모델링한다.
 * 결제(Payment)와 분리한 이유: 생명주기·관심사가 다르다(거래 승인 ↔ 자금 입금). 결제 엔티티에
 * {@code fee}·{@code settledDate}를 욱여넣으면 결제 도메인이 정산 걱정까지 떠안아 오염된다
 * (docs/payment-modern-architecture.md §3.5).
 *
 * <p>다른 애그리거트(결제·주문)는 객체 연관 대신 ID로 참조한다(architecture.md §11).
 *
 * <p><b>핵심: 매출 ≠ 셀러 실수령.</b> 셀러별 정산(Phase 2)에서는 한 결제가 셀러별로 쪼개진다 —
 * 항목은 {@code (payment_id, seller_id)} 단위다. 셀러 매출(grossAmount)에서 <b>PG 수수료 안분분</b>(fee)과
 * <b>플랫폼 판매수수료</b>(platformFee)를 떼면 셀러 실수령(netAmount)이 된다.
 * 브랜드 미지정/셀러 미귀속 항목은 sellerId=null(플랫폼 직매입 버킷).
 */
@Getter
@Entity
@Table(name = "settlement_entry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentId;          // 정산 대상 결제 (다른 애그리거트 → ID 참조)

    @Column(nullable = false)
    private Long orderId;            // 주문 (조회 편의를 위해 함께 보관 — 역시 ID 참조)

    @Column(nullable = false, length = 100)
    private String pgTransactionId;  // ★ 대사(reconciliation)의 조인 키 — P2에서 PG 리포트와 매칭한다

    @Column(nullable = false, length = 30)
    private String provider;         // 정산 대상 결제를 처리한 PG (예: TOSS, KAKAOPAY) — MPG-3에서 PG별 집계의 키

    private Long sellerId;           // 셀러(입점사) 참조(ID, nullable) — 셀러별 정산 귀속. null이면 플랫폼 직매입(미귀속)

    @Column(nullable = false)
    private long grossAmount;        // 이 셀러의 매출(원) — 주문 항목 중 해당 셀러분 소계 합

    @Column(nullable = false)
    private long fee;                // PG 수수료 안분분(원) — 결제 PG수수료를 셀러 매출 비례로 나눈 몫

    @Column(nullable = false)
    private double feeRate;          // 적용한 PG 수수료율 스냅샷 — 요율이 나중에 바뀌어도 "그때 몇 %로 뗐나"를 보존

    @Column(nullable = false)
    private long platformFee;        // 플랫폼 판매수수료(원) = grossAmount × platformFeeRate (셀러→플랫폼)

    @Column(nullable = false)
    private double platformFeeRate;  // 적용한 플랫폼 수수료율 스냅샷(= Seller.commissionRate 그때 값)

    @Column(nullable = false)
    private long netAmount;          // 셀러 실수령(원) = grossAmount - fee - platformFee  ← "매출 ≠ 실수령"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(nullable = false)
    private LocalDate settledDate;   // 입금(정산) 예정/완료일 (T+N)

    private SettlementEntry(Long paymentId, Long orderId, String pgTransactionId, String provider,
                            Long sellerId, long grossAmount, long fee, double feeRate,
                            long platformFee, double platformFeeRate, LocalDate settledDate) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.pgTransactionId = pgTransactionId;
        this.provider = provider;
        this.sellerId = sellerId;
        this.grossAmount = grossAmount;
        this.fee = fee;
        this.feeRate = feeRate;
        this.platformFee = platformFee;
        this.platformFeeRate = platformFeeRate;
        // 셀러 실수령은 파생값 — 엔티티가 스스로 계산해 일관성 보장(매출에서 PG수수료·플랫폼수수료를 뗀 값)
        this.netAmount = grossAmount - fee - platformFee;
        this.settledDate = settledDate;
        this.status = SettlementStatus.SCHEDULED;    // 생성 시점 = 입금 전(예정)
    }

    /** 정산 예정 항목 생성(셀러 단위). 수수료(PG 안분분·플랫폼)·요율은 정산 서비스가 계산해 넘겨준다. */
    public static SettlementEntry scheduled(Long paymentId, Long orderId, String pgTransactionId, String provider,
                                            Long sellerId, long grossAmount, long fee, double feeRate,
                                            long platformFee, double platformFeeRate, LocalDate settledDate) {
        return new SettlementEntry(paymentId, orderId, pgTransactionId, provider, sellerId,
                grossAmount, fee, feeRate, platformFee, platformFeeRate, settledDate);
    }

    /** 입금 확인 → PAID_OUT. (SCHEDULED 상태에서만 가능) */
    public void markPaidOut() {
        if (this.status != SettlementStatus.SCHEDULED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "정산 상태 전이가 올바르지 않습니다. (현재: " + this.status + ", 기대: " + SettlementStatus.SCHEDULED + ")");
        }
        this.status = SettlementStatus.PAID_OUT;
    }
}
