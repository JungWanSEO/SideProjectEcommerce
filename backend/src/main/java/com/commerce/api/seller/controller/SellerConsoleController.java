package com.commerce.api.seller.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.service.SellerConsoleService;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.entity.SettlementStatus;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 콘솔 API — 로그인한 셀러 본인 전용(SecurityConfig: /api/seller/** hasRole("SELLER")).
 * 셀러는 자기 sellerId로만 스코핑되어 남의 정산은 볼 수 없다. (ADMIN 전체 조회는 /api/settlements 별도.)
 *
 * - GET /api/seller/me              내 셀러 정보
 * - GET /api/seller/me/settlements  내 정산 항목(상태·기간 필터)
 * - GET /api/seller/me/summary      내 정산서(셀러별 집계)
 */
@Tag(name = "셀러 콘솔(Seller Console)", description = "로그인 셀러 본인 정산 조회 API (SELLER)")
@RestController
@RequestMapping("/api/seller")
@RequiredArgsConstructor
public class SellerConsoleController {

    private final SellerConsoleService sellerConsoleService;

    @Operation(summary = "내 셀러 정보", description = "로그인 셀러 본인의 셀러 정보. 셀러 계정이 아니면 403.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SellerResponse>> getMySeller() {
        return ResponseEntity.ok(
                ApiResponse.success(sellerConsoleService.getMySeller(SecurityUtil.getCurrentMemberId())));
    }

    @Operation(summary = "내 정산 항목", description = "본인 셀러의 정산 항목(상태·기간 필터). 최신순.")
    @GetMapping("/me/settlements")
    public ResponseEntity<ApiResponse<PageResponse<SettlementResponse>>> getMySettlements(
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMySettlements(
                        SecurityUtil.getCurrentMemberId(), status, from, to, pageable)));
    }

    @Operation(summary = "내 정산서", description = "본인 셀러의 매출·PG수수료·플랫폼수수료·실수령 집계.")
    @GetMapping("/me/summary")
    public ResponseEntity<ApiResponse<List<SellerSettlementSummary>>> getMySummary(
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMySummary(SecurityUtil.getCurrentMemberId(), status, from, to)));
    }

    @Operation(summary = "내 지급 내역", description = "본인 셀러의 지급 묶음(Payout) 목록. 상태 필터(선택).")
    @GetMapping("/me/payouts")
    public ResponseEntity<ApiResponse<PageResponse<PayoutResponse>>> getMyPayouts(
            @RequestParam(required = false) PayoutStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMyPayouts(SecurityUtil.getCurrentMemberId(), status, pageable)));
    }
}
