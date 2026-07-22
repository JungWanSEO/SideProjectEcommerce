package com.commerce.api.settlement.dto;

import com.commerce.api.settlement.entity.SettlementStatus;
import java.time.LocalDate;

/**
 * 정산 항목 조회 조건(전부 선택 — null이면 그 필터 무시).
 *
 * @param sellerId 셀러 필터
 * @param status   상태 필터(SCHEDULED/PAID_OUT)
 * @param provider PG 필터(TOSS/KAKAOPAY 등) — 대문자·blank→null로 정규화(저장 표기 일치)
 * @param from     정산(입금)일 시작 — settledDate &gt;= from
 * @param to       정산(입금)일 끝   — settledDate &lt;= to
 */
public record SettlementSearchCondition(
        Long sellerId,
        SettlementStatus status,
        String provider,
        LocalDate from,
        LocalDate to
) {
    /**
     * provider 정규화 — 대문자로 올리고 blank는 null로. 저장 표기(대문자)와 매칭을 맞추고,
     * 대사(ReconciliationService.getMismatches)의 provider 필터와 같은 규칙을 쓴다.
     */
    public SettlementSearchCondition {
        provider = (provider == null || provider.isBlank()) ? null : provider.toUpperCase();
    }

    /** provider 필터 없는 호출용(셀러 콘솔·기존 호출부 호환). */
    public SettlementSearchCondition(Long sellerId, SettlementStatus status, LocalDate from, LocalDate to) {
        this(sellerId, status, null, from, to);
    }
}
