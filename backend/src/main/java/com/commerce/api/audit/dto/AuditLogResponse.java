package com.commerce.api.audit.dto;

import com.commerce.api.audit.entity.AuditLog;
import com.commerce.api.audit.entity.AuditResult;
import java.time.LocalDateTime;

/**
 * 감사 로그 응답. 행위자 이메일(actorEmail)은 서비스가 회원 조회로 enrich(없으면 null).
 */
public record AuditLogResponse(
        Long id,
        Long actorMemberId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        String detail,
        AuditResult result,
        LocalDateTime createdAt
) {
    public static AuditLogResponse of(AuditLog log, String actorEmail) {
        return new AuditLogResponse(
                log.getId(),
                log.getActorMemberId(),
                actorEmail,
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getDetail(),
                log.getResult(),
                log.getCreatedAt()
        );
    }
}
