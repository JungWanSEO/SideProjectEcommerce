package com.commerce.api.audit.dto;

import com.commerce.api.audit.entity.AuditResult;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 감사 로그 검색 조건 — 쿼리 파라미터로 바인딩(@ParameterObject). 모두 선택(없으면 무시).
 *
 * @param actorMemberId 행위자 회원 ID
 * @param action        액션 코드(정확히 일치, 예: "PRODUCT_UPDATE")
 * @param targetType    대상 종류(정확히 일치, 예: "PRODUCT")
 * @param result        결과(SUCCESS/FAILURE)
 * @param from          이 시각 이상(포함) — ISO-8601, 예: 2026-07-06T00:00:00
 * @param to            이 시각 미만(제외)
 */
public record AuditLogSearchCondition(
        Long actorMemberId,
        String action,
        String targetType,
        AuditResult result,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
) {
}
