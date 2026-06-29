package com.commerce.api.dashboard.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.dashboard.dto.DashboardResponse;
import com.commerce.api.dashboard.service.DashboardService;
import com.commerce.api.order.entity.OrderStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * DashboardController 슬라이스 테스트 (@WebMvcTest, 보안 필터 비활성). ADMIN 인가는 런타임/통합으로.
 */
@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    @DisplayName("GET /api/dashboard - KPI·상태분포·추이를 200으로")
    void getDashboard() throws Exception {
        DashboardResponse resp = new DashboardResponse(
                new DashboardResponse.Kpi(10, 50000, 3000, 5, 8),
                List.of(new DashboardResponse.OrderStatusCount(OrderStatus.PAID, 4)),
                List.of(new DashboardResponse.DailyRevenue(LocalDate.of(2026, 6, 29), 50000)));
        given(dashboardService.getDashboard(anyInt())).willReturn(resp);

        mockMvc.perform(get("/api/dashboard").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpi.totalOrders").value(10))
                .andExpect(jsonPath("$.data.kpi.paidRevenue").value(50000))
                .andExpect(jsonPath("$.data.orderStatusDistribution[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.revenueTrend[0].revenue").value(50000));
    }
}
