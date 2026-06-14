package com.commerce.api.settlement.dto;

import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 정산 항목 응답. */
public record SettlementResponse(
        Long id,
        Long paymentId,
        Long orderId,
        String pgTransactionId,
        String provider,         // 정산 대상 결제를 처리한 PG (MPG-3)
        Long sellerId,           // 셀러(입점사) — null이면 플랫폼 직매입(미귀속)
        long grossAmount,        // 셀러 매출
        long fee,                // PG 수수료(안분)
        double feeRate,          // 적용한 PG 수수료율 스냅샷 (예: 0.025)
        long platformFee,        // 플랫폼 판매수수료
        double platformFeeRate,  // 적용한 플랫폼 수수료율 스냅샷 (예: 0.10)
        long netAmount,          // 셀러 실수령 (= grossAmount - fee - platformFee)
        SettlementStatus status,
        LocalDate settledDate,
        LocalDateTime createdAt
) {
    public static SettlementResponse from(SettlementEntry entry) {
        return new SettlementResponse(
                entry.getId(),
                entry.getPaymentId(),
                entry.getOrderId(),
                entry.getPgTransactionId(),
                entry.getProvider(),
                entry.getSellerId(),
                entry.getGrossAmount(),
                entry.getFee(),
                entry.getFeeRate(),
                entry.getPlatformFee(),
                entry.getPlatformFeeRate(),
                entry.getNetAmount(),
                entry.getStatus(),
                entry.getSettledDate(),
                entry.getCreatedAt()
        );
    }
}
