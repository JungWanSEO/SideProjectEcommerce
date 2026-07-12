package com.commerce.api.settlement.controller;

import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.settlement.dto.MismatchResolveRequest;
import com.commerce.api.settlement.dto.MismatchResponse;
import com.commerce.api.settlement.dto.ReconciliationResult;
import com.commerce.api.settlement.entity.MismatchStatus;
import com.commerce.api.settlement.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대사(Reconciliation) API — 전부 ADMIN 전용(SecurityConfig에서 /api/reconciliations/** 를 hasRole("ADMIN")).
 *
 * - POST /api/reconciliations/run                    대사 실행 (우리 정산 ↔ PG 리포트 대조)
 * - GET  /api/reconciliations/mismatches             불일치 목록 (status 필터)
 * - POST /api/reconciliations/mismatches/{id}/resolve 불일치 처리(상계·보정)
 * - POST /api/reconciliations/mismatches/{id}/ignore  불일치 무시(오탐)
 */
@Tag(name = "대사(Reconciliation)", description = "대사 실행 / 불일치 조회·처리 API (ADMIN)")
@RestController
@RequestMapping("/api/reconciliations")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @Operation(summary = "대사 실행",
            description = "우리 정산 항목과 PG 정산 리포트를 거래키(pgTransactionId)로 대조해 불일치를 분류·기록한다. "
                    + "from/to(정산일 기준, ISO yyyy-MM-dd, 둘 다 포함)를 주면 그 일자별 윈도우만 대조하고, "
                    + "비우면 전체를 본다. 이전 OPEN은 다시 스냅샷하되(윈도우면 그 거래키만) 이미 처리(RESOLVED/IGNORED)한 "
                    + "거래키는 다시 열지 않는다. 일치/새 불일치/이미 처리 건수를 요약 반환.")
    @Auditable(action = "RECONCILIATION_RUN", targetType = "RECONCILIATION")
    @PostMapping("/run")
    public ResponseEntity<ApiResponse<ReconciliationResult>> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReconciliationResult result = reconciliationService.reconcile(from, to);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("대사를 실행했습니다.", result));
    }

    @Operation(summary = "불일치 목록 조회",
            description = "불일치 항목을 페이지로 조회한다. status(OPEN/RESOLVED/IGNORED)·provider(TOSS/KAKAOPAY)로 "
                    + "각각 필터 가능(둘 다 없으면 전체). 기본 정렬은 최신순(id desc).")
    @GetMapping("/mismatches")
    public ResponseEntity<ApiResponse<PageResponse<MismatchResponse>>> getMismatches(
            @RequestParam(required = false) MismatchStatus status,
            @RequestParam(required = false) String provider,
            @ParameterObject
            @PageableDefault(size = 20, sort = "id", direction = Direction.DESC)
            Pageable pageable) {
        PageResponse<MismatchResponse> response = reconciliationService.getMismatches(status, provider, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "불일치 처리(resolve)",
            description = "불일치를 처리 완료(RESOLVED)로 표시한다. 처리 사유(note)는 선택. "
                    + "OPEN 상태에서만 가능(이미 종료면 409), 없으면 404. 처리된 거래키는 재대사에서 다시 열리지 않는다.")
    @Auditable(action = "MISMATCH_RESOLVE", targetType = "MISMATCH", targetId = "#id")
    @PostMapping("/mismatches/{id}/resolve")
    public ResponseEntity<ApiResponse<MismatchResponse>> resolve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) MismatchResolveRequest request) {
        String note = (request == null) ? null : request.note();
        MismatchResponse response = reconciliationService.resolve(id, note);
        return ResponseEntity.ok(ApiResponse.success("불일치를 처리했습니다.", response));
    }

    @Operation(summary = "불일치 무시(ignore)",
            description = "불일치를 무시(IGNORED, 오탐·허용)로 표시한다. OPEN에서만 가능(아니면 409), 없으면 404.")
    @Auditable(action = "MISMATCH_IGNORE", targetType = "MISMATCH", targetId = "#id")
    @PostMapping("/mismatches/{id}/ignore")
    public ResponseEntity<ApiResponse<MismatchResponse>> ignore(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) MismatchResolveRequest request) {
        String note = (request == null) ? null : request.note();
        MismatchResponse response = reconciliationService.ignore(id, note);
        return ResponseEntity.ok(ApiResponse.success("불일치를 무시 처리했습니다.", response));
    }
}
