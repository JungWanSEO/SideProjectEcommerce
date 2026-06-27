package com.commerce.api.dashboard.controller;

import com.commerce.api.dashboard.dto.DashboardResponse;
import com.commerce.api.dashboard.service.DashboardService;
import com.commerce.api.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 대시보드 API — 운영 요약 한 화면(ADMIN 전용, SecurityConfig {@code /api/dashboard/**}).
 * - GET /api/dashboard?days=30   KPI + 주문 상태 분포 + 최근 days일 매출 추이
 */
@Tag(name = "어드민 대시보드(Dashboard)", description = "운영 요약 집계 API")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "대시보드 요약", description = "KPI(주문/매출/정산대기/회원/상품)·주문 상태별 분포·최근 days일 일별 매출 추이를 한 번에 조회한다. days 기본 30(1~90).")
    @GetMapping
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getDashboard(days)));
    }
}
