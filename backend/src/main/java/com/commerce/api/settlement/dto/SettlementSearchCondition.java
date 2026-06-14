package com.commerce.api.settlement.dto;

import com.commerce.api.settlement.entity.SettlementStatus;
import java.time.LocalDate;

/**
 * 정산 항목 조회 조건(전부 선택 — null이면 그 필터 무시).
 *
 * @param sellerId 셀러 필터
 * @param status   상태 필터(SCHEDULED/PAID_OUT)
 * @param from     정산(입금)일 시작 — settledDate &gt;= from
 * @param to       정산(입금)일 끝   — settledDate &lt;= to
 */
public record SettlementSearchCondition(
        Long sellerId,
        SettlementStatus status,
        LocalDate from,
        LocalDate to
) {
}
