package com.commerce.api.payment.repository;

import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.entity.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** 멱등키로 기존 결제 조회 — 같은 키의 중복 요청을 감지해 재실행을 막는다. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** 특정 상태의 결제 전체 조회 — 정산 배치가 PAID 결제를 모아갈 때 쓴다. */
    List<Payment> findByStatus(PaymentStatus status);

    /**
     * 여러 상태의 결제 조회 — 정산 <b>역분개</b> 후보(PAID + CANCELLED, #3 P5). 반품 전액환불은 Payment를
     * CANCELLED로 넘기므로 PAID만 스캔하면 역분개가 누락돼 셀러 과다정산이 난다. 정산된 모든 결제를 후보로 둔다
     * (이미 상계된 건은 diff 0이라 멱등). 정방향 정산 run()은 계속 PAID만(findByStatus).
     */
    List<Payment> findByStatusIn(java.util.Collection<PaymentStatus> statuses);

    /**
     * 주문의 특정 상태 결제 조회 — 환불 시 PAID 한 건을 찾는 데 쓴다.
     * (한 주문에 결제 시도가 여러 번이어도 PAID는 최대 1건 → Optional로 안전.)
     */
    Optional<Payment> findByOrderIdAndStatus(Long orderId, PaymentStatus status);

    /**
     * <b>순매출</b>(환불 차감) 합 — 결제의 {@code amount − refundedAmount} 합. 대시보드 "완료 매출" KPI.
     *
     * <p>주문 기준 gross(totalPrice−discount)는 부분취소된 항목까지 매출로 세 결제·정산 net과 어긋난다.
     * refundedAmount가 부분환불을 누적하므로 {@code amount − refundedAmount}가 실제 받은 순매출과 정확히 일치한다
     * (전액 환불되면 결제가 CANCELLED로 빠져 제외). PAID로 조회하면 배송중/완료 주문의 결제까지 포함된다.
     */
    @Query("select coalesce(sum(p.amount - p.refundedAmount), 0) from Payment p where p.status = :status")
    long sumNetRevenueByStatus(@Param("status") PaymentStatus status);

    /** 일별 순매출 추이용 — 기간 내 그 상태 결제의 (결제 시각, amount − refundedAmount). */
    @Query("select p.createdAt, (p.amount - p.refundedAmount) from Payment p "
            + "where p.status = :status and p.createdAt >= :since")
    List<Object[]> findNetAmountsByStatusSince(
            @Param("status") PaymentStatus status, @Param("since") LocalDateTime since);
}
