package com.commerce.api.seller.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.entity.SellerStatus;
import com.commerce.api.seller.service.SellerConsoleService;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.entity.PayoutStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SellerConsoleController 통합 테스트 (@WebMvcTest + MockMvc, 보안 필터 비활성).
 * 로그인 셀러(principal=1L, ROLE_SELLER) 주입 — 컨트롤러는 SecurityUtil로 회원 ID를 꺼낸다.
 */
@WebMvcTest(SellerConsoleController.class)
@AutoConfigureMockMvc(addFilters = false)
class SellerConsoleControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private SellerConsoleService sellerConsoleService;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L, null, List.of(new SimpleGrantedAuthority("ROLE_SELLER"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/seller/me - 내 셀러 정보 200")
    void getMySeller() throws Exception {
        given(sellerConsoleService.getMySeller(1L)).willReturn(
                new SellerResponse(5L, "UrbanSelect", 0.10, SellerStatus.ACTIVE, null, null, LocalDateTime.now()));

        mockMvc.perform(get("/api/seller/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("UrbanSelect"));
    }

    @Test
    @DisplayName("GET /api/seller/me/summary - 내 정산서 200")
    void getMySummary() throws Exception {
        given(sellerConsoleService.getMySummary(eq(1L), any(), any(), any())).willReturn(
                List.of(new SellerSettlementSummary(5L, "UrbanSelect", 1, 10000, 250, 1000, 0, 8750)));

        mockMvc.perform(get("/api/seller/me/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sellerName").value("UrbanSelect"))
                .andExpect(jsonPath("$.data[0].netAmount").value(8750));
    }

    @Test
    @DisplayName("GET /api/seller/me/settlements - 내 정산 항목 200")
    void getMySettlements() throws Exception {
        given(sellerConsoleService.getMySettlements(eq(1L), any(), any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/api/seller/me/settlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/seller/me/payouts - 내 지급 내역 200")
    void getMyPayouts() throws Exception {
        given(sellerConsoleService.getMyPayouts(eq(1L), any(), any())).willReturn(
                new PageResponse<>(
                        List.of(new PayoutResponse(1L, 5L, "UrbanSelect", LocalDate.now(), LocalDate.now(),
                                30000, 750, 3000, 26250, 2, PayoutStatus.PENDING, null, LocalDateTime.now())),
                        0, 20, 1, 1, false));

        mockMvc.perform(get("/api/seller/me/payouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].totalNet").value(26250));
    }
}
