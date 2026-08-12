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

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;     // 이 항목에 안분된 쿠폰 할인액(원). 할인 없으면 0

    @Column(name = "discount_funded_by", length = 20)
    private String discountFundedBy; // 할인 부담 주체 스냅샷("PLATFORM"/"SELLER", 없으면 null) — net 분담에 사용

    @Column(nullable = false)
    private long netAmount;          // 셀러 실수령(원). "매출 ≠ 실수령"

    /**
     * 배송비(플랫폼 수익) 엔트리 표시(#4). true면 sellerId=null·gross=배송비·platformFee=0인 <b>플랫폼 배송비</b> 항목이다.
     * 대사는 Σgross에 이걸 포함해 PG 금액과 맞추고(MATCHED), reverseRefunds는 이걸 셀러 집계에서 분리해
     * 전체취소 때만 역분개한다(부분취소·반품은 배송비 유지). 셀러 net에는 절대 섞이지 않는다.
     */
    @Column(nullable = false)
    private boolean shipping;

    /**
     * 항목 종류(#8 후속) — {@code shipping} boolean을 대체하는 축. 이행 기간 동안 두 컬럼을 함께 쓰되
     * <b>판단은 항상 이 값</b>이 하고, shipping은 kind에서 파생해 채운다(이중 출처가 갈리지 않게).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_kind", nullable = false, length = 20)
    private SettlementEntryKind entryKind;

    /**
     * 셀러 귀책 과금(원, #8 후속 P4) — net에서 차감된다. FAULT_CHARGE 외 종류는 0.
     * gross가 아니라 별도 컬럼인 이유: 셀러↔플랫폼 내부 조정액이라 PG 원장에 대응 금액이 없다.
     * gross에 실으면 대사(Σgross = PG 승인액)가 즉시 AMOUNT_MISMATCH로 튄다.
     */
    @Column(name = "charge_amount", nullable = false)
    private long chargeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(name = "payout_id")
    private Long payoutId;           // 묶인 지급(Payout) ID(ID 참조, nullable). 묶음에 들어가면 설정됨.

    @Column(nullable = false)
    private LocalDate settledDate;   // 입금(정산) 예정/완료일 (T+N)

    /** 할인 부담 주체 — 문자열 스냅샷(settlement→coupon 결합 회피). 정산 net 환원 판정에만 쓴다. */
    private static final String FUNDED_BY_PLATFORM = "PLATFORM";

    private SettlementEntry(Long paymentId, Long orderId, String pgTransactionId, String provider,
                            Long sellerId, long grossAmount, long fee, double feeRate,
                            long platformFee, double platformFeeRate,
                            long discountAmount, String discountFundedBy,
                            SettlementEntryKind entryKind, long chargeAmount, LocalDate settledDate) {
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
        this.discountAmount = discountAmount;
        this.discountFundedBy = discountFundedBy;
        this.entryKind = entryKind;
        this.chargeAmount = chargeAmount;
        this.shipping = entryKind == SettlementEntryKind.SHIPPING;   // kind에서 파생(이행 기간 이중 출처가 갈리지 않게)
        // 셀러 실수령은 파생값(엔티티가 스스로 계산). gross(할인 후 몫)에서 PG·플랫폼 수수료를 떼되,
        // 플랫폼 부담 할인이면 그만큼 셀러에게 환원(subsidy) — 셀러는 할인 없이 받은 것과 같아지고 플랫폼이 부담.
        // 셀러 부담 할인이면 환원 없음(gross가 이미 줄어 셀러가 부담). 배송비 엔트리는 할인 없음(net = 배송비 − PG수수료).
        // 귀책 과금(FAULT_CHARGE)은 gross 없이 chargeAmount만 있어 net = −과금액이 된다.
        long subsidy = FUNDED_BY_PLATFORM.equals(discountFundedBy) ? discountAmount : 0L;
        this.netAmount = grossAmount - fee - platformFee + subsidy - chargeAmount;
        this.settledDate = settledDate;
        this.status = SettlementStatus.SCHEDULED;    // 생성 시점 = 입금 전(예정)
    }

    /** 정산 예정 항목 생성(셀러 단위, 할인 없음). 기존 호출부 호환용 — 할인=0/부담주체=null로 위임. */
    public static SettlementEntry scheduled(Long paymentId, Long orderId, String pgTransactionId, String provider,
                                            Long sellerId, long grossAmount, long fee, double feeRate,
                                            long platformFee, double platformFeeRate, LocalDate settledDate) {
        return scheduled(paymentId, orderId, pgTransactionId, provider, sellerId,
                grossAmount, fee, feeRate, platformFee, platformFeeRate, 0L, null, settledDate);
    }

    /** 정산 예정 항목 생성(셀러 단위, 할인 반영). gross는 '할인 후 셀러 몫', discountAmount는 안분된 할인액. */
    public static SettlementEntry scheduled(Long paymentId, Long orderId, String pgTransactionId, String provider,
                                            Long sellerId, long grossAmount, long fee, double feeRate,
                                            long platformFee, double platformFeeRate,
                                            long discountAmount, String discountFundedBy, LocalDate settledDate) {
        return new SettlementEntry(paymentId, orderId, pgTransactionId, provider, sellerId,
                grossAmount, fee, feeRate, platformFee, platformFeeRate,
                discountAmount, discountFundedBy, SettlementEntryKind.SALE, 0L, settledDate);
    }

    /**
     * 배송비(플랫폼 수익) 정산 항목 생성(#4) — sellerId=null·platformFee=0·할인 0·shipping=true.
     * gross=배송비, fee=배송비에 붙는 PG수수료(플랫폼 부담) → net = 배송비 − PG수수료. 대사 Σgross 복원용이자
     * 플랫폼 배송 매출 원장. 역분개 시 grossAmount·fee에 음수가 올 수 있다(전체취소 상계).
     */
    public static SettlementEntry shippingScheduled(Long paymentId, Long orderId, String pgTransactionId,
                                                    String provider, long grossAmount, long fee, double feeRate,
                                                    LocalDate settledDate) {
        return new SettlementEntry(paymentId, orderId, pgTransactionId, provider, null,
                grossAmount, fee, feeRate, 0L, 0.0, 0L, null, SettlementEntryKind.SHIPPING, 0L, settledDate);
    }

    /**
     * 플랫폼 <b>반품 회수비</b> 수익 엔트리(#8 후속 P3) — sellerId=null·platformFee=0·kind=RETURN_SHIPPING.
     *
     * <p>고객 귀책 반품에서 환불을 줄여 플랫폼이 실제로 보유한 금액이다. 배송비 엔트리와 같은 이유로 gross에
     * 실어 원장 총액을 복원한다(PG 잔여에 실재하는 돈이므로). 역분개 시 음수가 올 수 있다.
     */
    public static SettlementEntry returnShippingScheduled(Long paymentId, Long orderId, String pgTransactionId,
                                                          String provider, long grossAmount, long fee, double feeRate,
                                                          LocalDate settledDate) {
        return new SettlementEntry(paymentId, orderId, pgTransactionId, provider, null,
                grossAmount, fee, feeRate, 0L, 0.0, 0L, null, SettlementEntryKind.RETURN_SHIPPING, 0L, settledDate);
    }

    /**
     * <b>셀러 귀책 과금</b> 엔트리(#8 후속 P4) — sellerId=셀러·gross=0·chargeAmount=금액 → net = −금액.
     *
     * <p>gross를 0으로 두는 것이 핵심이다: 이건 셀러↔플랫폼 내부 정산 조정액이라 PG 원장에 대응 금액이 없다.
     * gross에 실으면 대사가 즉시 AMOUNT_MISMATCH로 튄다. 역분개 시 chargeAmount에 음수가 올 수 있다.
     */
    public static SettlementEntry faultCharge(Long paymentId, Long orderId, String pgTransactionId, String provider,
                                              Long sellerId, long chargeAmount, LocalDate settledDate) {
        return new SettlementEntry(paymentId, orderId, pgTransactionId, provider, sellerId,
                0L, 0L, 0.0, 0L, 0.0, 0L, null, SettlementEntryKind.FAULT_CHARGE, chargeAmount, settledDate);
    }

    /** 지급 묶음(Payout)에 편입. */
    public void assignPayout(Long payoutId) {
        this.payoutId = payoutId;
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
