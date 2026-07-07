package com.commerce.api.audit.controller;

import com.commerce.api.audit.dto.AuditLogResponse;
import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.service.AuditLogService;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 감사 로그 조회 API — 운영 전용(SecurityConfig {@code /api/audit-logs/**} hasRole ADMIN).
 * 감사 로그 <b>적재</b>는 {@code @Auditable} + AuditAspect가 자동으로 하고, 여기선 <b>조회</b>만 한다.
 * - GET /api/audit-logs?actorMemberId=&action=&targetType=&result=&from=&to=  최신순 페이지
 */
@Tag(name = "감사 로그(Audit Log)", description = "어드민 변경 이력 조회 API (ADMIN)")
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "감사 로그 조회(ADMIN)",
            description = "어드민 변경 이력을 최신순으로 조회한다. 선택 필터: actorMemberId(행위자)·action(액션 코드)·"
                    + "targetType(대상 종류)·result(SUCCESS/FAILURE)·from/to(기간, ISO-8601). 기본 페이지 크기 20.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> search(
            @ParameterObject AuditLogSearchCondition condition,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.search(condition, pageable)));
    }
}
