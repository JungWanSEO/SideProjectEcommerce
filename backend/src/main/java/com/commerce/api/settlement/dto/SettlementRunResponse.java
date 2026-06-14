package com.commerce.api.settlement.dto;

import java.util.List;

/**
 * 정산 배치 실행 결과 요약.
 *
 * <p>이번 실행으로 새로 만든 정산 항목 수와 합계(매출/PG수수료/플랫폼수수료/실수령)를 돌려준다.
 *
 * <p><b>셀러별 정산(Phase 2):</b> 한 결제가 셀러별로 쪼개져 항목이 만들어진다. {@code bySeller}로
 * 셀러별 분해를, {@code byProvider}로 PG별 분해(MPG-3)를 함께 준다.
 * netAmount = grossAmount - fee(PG수수료 안분) - platformFee(플랫폼 판매수수료).
 */
public record SettlementRunResponse(
        int createdCount,         // 이번 실행으로 새로 만든 정산 항목 수((결제×셀러) 단위)
        long totalGrossAmount,    // 매출 합계
        long totalFee,            // PG 수수료 합계
        long totalPlatformFee,    // 플랫폼 판매수수료 합계
        long totalNetAmount,      // 셀러 실수령 합계 (= 매출 - PG수수료 - 플랫폼수수료)
        List<ProviderBreakdown> byProvider,   // PG별 분해 (MPG-3)
        List<SellerBreakdown> bySeller        // 셀러별 분해 (Phase 2 — 누구에게 얼마를 지급하나)
) {

    /** PG 한 곳의 정산 분해 — 요율·건수·금액 합계. */
    public record ProviderBreakdown(
            String provider,         // PG 코드 (예: TOSS, KAKAOPAY)
            double feeRate,          // 그 PG에 적용한 수수료율 (예: 0.025)
            int count,               // 이 PG로 만든 정산 항목 수
            long grossAmount,        // 매출 합계
            long fee,                // PG 수수료 합계
            long platformFee,        // 플랫폼 수수료 합계
            long netAmount           // 셀러 실수령 합계
    ) {
    }

    /** 셀러 한 곳의 정산 분해 — 매출/수수료/실수령 합계. sellerId가 null이면 플랫폼 직매입(미귀속). */
    public record SellerBreakdown(
            Long sellerId,           // 셀러 ID (null = 미귀속/플랫폼 직매입)
            int count,               // 이 셀러로 만든 정산 항목 수
            long grossAmount,        // 셀러 매출 합계
            long fee,                // PG 수수료(안분) 합계
            long platformFee,        // 플랫폼 판매수수료 합계
            long netAmount           // 셀러 실수령 합계
    ) {
    }
}
