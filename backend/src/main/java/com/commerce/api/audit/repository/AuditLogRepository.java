package com.commerce.api.audit.repository;

import com.commerce.api.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 감사 로그 DB 접근. 기본 CRUD(JpaRepository) + 동적 검색(AuditLogRepositoryCustom).
 * 스프링 데이터가 {@code AuditLogRepositoryImpl}을 자동으로 엮어준다(관례).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, AuditLogRepositoryCustom {
}
