package com.commerce.api.audit.repository;

import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 감사 로그 동적 검색(QueryDSL). 결과는 항상 최신순(created_at desc).
 */
public interface AuditLogRepositoryCustom {

    Page<AuditLog> search(AuditLogSearchCondition condition, Pageable pageable);
}
