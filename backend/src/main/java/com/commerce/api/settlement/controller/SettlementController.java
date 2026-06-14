package com.commerce.api.settlement.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.dto.SettlementReverseResponse;
import com.commerce.api.settlement.dto.SettlementRunResponse;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정산(Settlement) API — 전부 ADMIN 전용(SecurityConfig에서 /api/settlements/** 를 hasRole("ADMIN")).
 *
 * - POST /api/settlements/run         정산 배치 실행 (PAID 결제 → 정산 항목 생성)
 * - GET  /api/settlements             정산 항목 목록 (페이지)
 * - POST /api/settlements/{id}/payout 입금 확인 (SCHEDULED → PAID_OUT)
 */
@Tag(name = "정산(Settlement)", description = "정산 배치 / 조회 API (ADMIN)")
@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    @Operation(summary = "정산 배치 실행",
            description = "PAID 결제 중 아직 정산되지 않은 건을 모아 정산 항목(SCHEDULED)을 만든다. "
                    + "수수료를 떼고 실입금(매출)을 계산한다. 여러 번 실행해도 중복 생성되지 않는다(멱등).")
    @PostMapping("/run")
    public ResponseEntity<ApiResponse<SettlementRunResponse>> run() {
        SettlementRunResponse response = settlementService.run();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("정산 배치를 실행했습니다.", response));
    }

    @Operation(summary = "정산 항목 목록 조회",
            description = "정산 항목을 페이지로 조회한다. 셀러/상태/기간(정산일) 필터(선택). 기본 정렬 최신순(id desc), 기본 크기 20.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<SettlementResponse>>> getSettlements(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject
            @PageableDefault(size = 20, sort = "id", direction = Direction.DESC)
            Pageable pageable) {
        SettlementSearchCondition condition = new SettlementSearchCondition(sellerId, status, from, to);
        PageResponse<SettlementResponse> response = settlementService.getSettlements(condition, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "환불 상계(역분개) 배치",
            description = "부분환불로 취소된 항목의 정산을 음수(역분개) 항목으로 상계한다. 멱등(여러 번 실행해도 안전).")
    @PostMapping("/reverse-refunds")
    public ResponseEntity<ApiResponse<SettlementReverseResponse>> reverseRefunds() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("환불 상계를 실행했습니다.", settlementService.reverseRefunds()));
    }

    @Operation(summary = "셀러 정산서(셀러별 집계)",
            description = "조건(셀러/상태/기간) 범위에서 셀러별 매출·PG수수료·플랫폼수수료·실수령을 집계한다.")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<List<SellerSettlementSummary>>> sellerSummary(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        SettlementSearchCondition condition = new SettlementSearchCondition(sellerId, status, from, to);
        return ResponseEntity.ok(ApiResponse.success(settlementService.getSellerSummary(condition)));
    }

    @Operation(summary = "입금 확인",
            description = "정산 항목을 입금 완료(PAID_OUT)로 표시한다. SCHEDULED 상태에서만 가능(아니면 409), 없으면 404.")
    @PostMapping("/{id}/payout")
    public ResponseEntity<ApiResponse<SettlementResponse>> payout(@PathVariable Long id) {
        SettlementResponse response = settlementService.payout(id);
        return ResponseEntity.ok(ApiResponse.success("입금 완료로 처리했습니다.", response));
    }
}
