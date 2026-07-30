package com.commerce.api.dashboard.controller;

import com.commerce.api.dashboard.dto.CancelReasonStatsResponse;
import com.commerce.api.dashboard.dto.DashboardResponse;
import com.commerce.api.dashboard.dto.LowStockResponse;
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
 * <ul>
 *   <li>GET /api/dashboard?days=30                     KPI + 주문 상태 분포 + 최근 days일 매출 추이
 *   <li>GET /api/dashboard/low-stock?threshold=5&limit=10  재고 임박·품절 옵션(사이즈) 리포트
 *   <li>GET /api/dashboard/cancel-reasons                 취소·반품 사유별·귀책별 집계(#8 후속)
 * </ul>
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

    @Operation(summary = "재고 임박·품절 리포트",
            description = "재고가 threshold 이하인 상품 옵션(사이즈)을 재고 적은 순으로 조회한다. "
                    + "전체 품절/임박 건수 + 상위 limit개 목록. threshold 기본 5(0~100), limit 기본 10(1~100). "
                    + "판매중지 상품은 제외하며, 재고는 실시간이라 캐시하지 않는다.")
    @GetMapping("/low-stock")
    public ResponseEntity<ApiResponse<LowStockResponse>> getLowStock(
            @RequestParam(defaultValue = "5") int threshold,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getLowStock(threshold, limit)));
    }

    @Operation(summary = "취소·반품 사유 집계",
            description = "취소(주문 항목)와 반품/교환 요청의 사유를 사유별·귀책(CUSTOMER/SELLER/PLATFORM/NONE)별로 "
                    + "집계한다. 사유는 add-only·nullable로 도입돼 이전 데이터엔 없으므로 미기록 건수를 따로 준다. "
                    + "전체 기간 기준(취소 시각을 별도 보관하지 않아 기간 필터는 후속).")
    @GetMapping("/cancel-reasons")
    public ResponseEntity<ApiResponse<CancelReasonStatsResponse>> getCancelReasonStats() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getCancelReasonStats()));
    }
}
