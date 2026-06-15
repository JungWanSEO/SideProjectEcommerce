package com.commerce.api.settlement.dto;

/**
 * 셀러 정산서 — 한 셀러의 정산 집계(조회 조건 범위 안에서).
 *
 * <p>"이 셀러에게 매출 얼마 중 PG수수료·플랫폼수수료를 떼고 얼마를 지급하나"를 한 줄로.
 * sellerId가 null이면 미귀속(플랫폼 직매입) 버킷, sellerName도 null.
 */
public record SellerSettlementSummary(
        Long sellerId,
        String sellerName,    // 셀러명(서비스가 enrich) — 미귀속이면 null
        long count,           // 정산 항목 수
        long grossAmount,     // 매출 합계(할인 후 셀러 몫)
        long fee,             // PG 수수료(안분) 합계
        long platformFee,     // 플랫폼 판매수수료 합계
        long discountAmount,  // 쿠폰 할인 합계(이 셀러분에 안분된)
        long netAmount        // 셀러 실수령 합계 (원매출 = grossAmount + discountAmount)
) {
}
