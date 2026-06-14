package com.commerce.api.settlement.dto;

import com.commerce.api.settlement.entity.Payout;
import com.commerce.api.settlement.entity.PayoutStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 지급 묶음 응답. sellerName은 서비스가 enrich.
 */
public record PayoutResponse(
        Long id,
        Long sellerId,
        String sellerName,
        LocalDate periodFrom,
        LocalDate periodTo,
        long totalGross,
        long totalFee,
        long totalPlatformFee,
        long totalNet,        // 실지급액
        int entryCount,
        PayoutStatus status,
        LocalDateTime paidAt,
        LocalDateTime createdAt
) {
    public static PayoutResponse from(Payout p, String sellerName) {
        return new PayoutResponse(
                p.getId(), p.getSellerId(), sellerName, p.getPeriodFrom(), p.getPeriodTo(),
                p.getTotalGross(), p.getTotalFee(), p.getTotalPlatformFee(), p.getTotalNet(),
                p.getEntryCount(), p.getStatus(), p.getPaidAt(), p.getCreatedAt());
    }
}
