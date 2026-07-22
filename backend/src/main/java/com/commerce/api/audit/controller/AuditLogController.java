package com.commerce.api.audit.controller;

import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.audit.dto.AuditLogResponse;
import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.service.AuditLogService;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 어드민 감사 로그 조회 API — 운영 전용(SecurityConfig {@code /api/audit-logs/**} hasRole ADMIN).
 * 감사 로그 <b>적재</b>는 {@code @Auditable} + AuditAspect가 자동으로 하고, 여기선 <b>조회</b>만 한다.
 * <ul>
 *   <li>GET /api/audit-logs?actorMemberId=&action=&targetType=&targetId=&result=&from=&to=  최신순 페이지
 *   <li>GET /api/audit-logs/export?(같은 필터)                                            CSV 내보내기(스트리밍)
 * </ul>
 */
@Tag(name = "감사 로그(Audit Log)", description = "어드민 변경 이력 조회 API (ADMIN)")
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "감사 로그 조회(ADMIN)",
            description = "어드민 변경 이력을 최신순으로 조회한다. 선택 필터: actorMemberId(행위자)·action(액션 코드)·"
                    + "targetType(대상 종류)·targetId(대상 식별자)·result(SUCCESS/FAILURE)·from/to(기간, ISO-8601). "
                    + "기본 페이지 크기 20.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> search(
            @ParameterObject AuditLogSearchCondition condition,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(auditLogService.search(condition, pageable)));
    }

    /**
     * CSV 내보내기. 목록과 <b>같은 필터</b>를 그대로 받아 "지금 보고 있는 결과"를 파일로 준다.
     *
     * <p>{@link StreamingResponseBody}라 전체를 메모리에 모으지 않고 <b>흘려 보낸다</b>(서비스가 청크로 읽어 바로 write).
     * 콜백은 컨트롤러 반환 <b>이후</b>에 실행되므로 트랜잭션도 그때(서비스 메서드 진입 시) 열린다.
     *
     * <p>감사 로그를 뽑아가는 것 자체가 민감한 행위라 <b>내보내기도 감사</b>한다(AUDIT_EXPORT).
     */
    @Operation(summary = "감사 로그 CSV 내보내기(ADMIN)",
            description = "목록과 같은 필터로 감사 로그를 CSV(UTF-8 BOM)로 내려받는다. 최대 5만 행(초과 시 파일 끝에 안내). "
                    + "내보내기 행위 자체도 감사 로그에 남는다(AUDIT_EXPORT).")
    @Auditable(action = "AUDIT_EXPORT", targetType = "AUDIT_LOG")
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@ParameterObject AuditLogSearchCondition condition) {
        StreamingResponseBody body = out -> {
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                auditLogService.exportCsv(condition, writer);
            }
        };

        String filename = "audit-logs-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
