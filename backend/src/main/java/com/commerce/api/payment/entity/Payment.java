package com.commerce.api.payment.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 결제 (애그리거트 루트).
 *
 * - 주문(Order)은 다른 애그리거트 → memberId처럼 ID(orderId)로 참조한다(객체연관 X, architecture.md §11).
 * - 상태 전이 규칙(READY→PAID/FAILED, PAID→CANCELLED)을 엔티티가 스스로 강제한다 → 잘못된 전이는 예외.
 * - idempotencyKey: 같은 결제 요청의 중복 실행을 막는 멱등키(unique).
 */
@Getter
@Entity
@Table(name = "payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;            // 다른 애그리거트(주문) → ID 참조

    @Column(nullable = false)
    private long amount;             // 결제 금액(원). KRW는 정수.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, length = 30)
    private String method;          // 결제수단 (모의 단계라 문자열, 예: "MOCK_CARD")

    @Column(nullable = false, length = 30)
    private String provider;        // 결제를 처리한 PG (예: "TOSS", "KAKAOPAY") — 환불·정산·대사가 같은 PG를 가리키게 함

    @Column(length = 100)
    private String pgTransactionId; // PG 승인 후 받는 거래 ID. 승인 전에는 null.

    @Column(nullable = false, unique = true, length = 80)
    private String idempotencyKey;  // 중복 결제 방지용 멱등키

    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;    // 누적 환불액(부분환불). amount에 도달하면 전액 환불 → CANCELLED.

    private Payment(Long orderId, long amount, String method, String provider, String idempotencyKey) {
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.provider = provider;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.READY;   // 생성 시점 = 승인 전(준비)
    }

    /** 결제 시도 레코드 생성 (아직 승인 전 = READY). provider = 결제를 보낼 PG. */
    public static Payment ready(Long orderId, long amount, String method, String provider, String idempotencyKey) {
        return new Payment(orderId, amount, method, provider, idempotencyKey);
    }

    /** PG 승인 성공 → PAID. (READY 상태에서만 가능) */
    public void markPaid(String pgTransactionId) {
        requireStatus(PaymentStatus.READY);
        this.pgTransactionId = pgTransactionId;
        this.status = PaymentStatus.PAID;
    }

    /** PG 승인 실패 → FAILED. (READY 상태에서만 가능) */
    public void markFailed() {
        requireStatus(PaymentStatus.READY);
        this.status = PaymentStatus.FAILED;
    }

    /** 결제 취소/환불 → CANCELLED. (PAID 상태에서만 가능) */
    public void cancel() {
        requireStatus(PaymentStatus.PAID);
        this.status = PaymentStatus.CANCELLED;
    }

    /**
     * 부분 환불 — 환불액을 누적한다(PAID 상태에서만). 누적이 결제액에 도달하면 전액 환불로 CANCELLED.
     * 금액이 0 이하거나 남은 환불 가능액을 넘으면 400.
     */
    public void partialRefund(long refundAmount) {
        requireStatus(PaymentStatus.PAID);
        if (refundAmount <= 0 || this.refundedAmount + refundAmount > this.amount) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "환불 금액이 올바르지 않습니다.");
        }
        this.refundedAmount += refundAmount;
        if (this.refundedAmount == this.amount) {
            this.status = PaymentStatus.CANCELLED;   // 전액 환불 완료
        }
    }

    /** 현재 상태가 기대 상태가 아니면 409. (상태머신 가드) */
    private void requireStatus(PaymentStatus expected) {
        if (this.status != expected) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "결제 상태 전이가 올바르지 않습니다. (현재: " + this.status + ", 기대: " + expected + ")");
        }
    }
}
