package com.commerce.api.settlement.dto;

/**
 * 환불 상계(역분개) 배치 결과 요약.
 *
 * @param reversedCount    이번 실행으로 만든 역분개 정산 항목 수
 * @param totalReversedNet 역분개 실수령 합계(보통 음수 — 환불로 줄어든 만큼)
 */
public record SettlementReverseResponse(
        int reversedCount,
        long totalReversedNet
) {
}
