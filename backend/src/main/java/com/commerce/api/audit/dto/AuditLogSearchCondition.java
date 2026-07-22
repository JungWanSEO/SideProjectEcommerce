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
 * @param targetId      대상 식별자(정확히 일치, 예: "42") — targetType과 함께 "그 대상의 전체 이력"을 뽑는다
 * @param result        결과(SUCCESS/FAILURE)
 * @param from          이 시각 이상(포함) — ISO-8601, 예: 2026-07-06T00:00:00
 * @param to            이 시각 미만(제외)
 */
public record AuditLogSearchCondition(
        Long actorMemberId,
        String action,
        String targetType,
        String targetId,
        AuditResult result,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
) {
    /** targetId 없는 호출용(기존 호출부·테스트 호환). @ParameterObject 바인딩은 canonical(7-arg)을 쓴다. */
    public AuditLogSearchCondition(Long actorMemberId, String action, String targetType,
            AuditResult result, LocalDateTime from, LocalDateTime to) {
        this(actorMemberId, action, targetType, null, result, from, to);
    }
}
