package com.commerce.api.settlement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.service.PayoutService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PayoutController 통합 테스트 (@WebMvcTest + MockMvc, 보안 필터 비활성).
 */
@WebMvcTest(PayoutController.class)
@AutoConfigureMockMvc(addFilters = false)
class PayoutControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private PayoutService payoutService;

    private PayoutResponse sample(PayoutStatus status) {
        return new PayoutResponse(1L, 5L, "UrbanSelect", LocalDate.now(), LocalDate.now().plusDays(7),
                30000, 750, 3000, 26250, 0, 0, 2, status, status == PayoutStatus.PAID ? LocalDateTime.now() : null,
                LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/payouts - 생성 201")
    void create_success() throws Exception {
        given(payoutService.create(any())).willReturn(sample(PayoutStatus.PENDING));

        mockMvc.perform(post("/api/payouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":5,\"from\":\"2026-06-14\",\"to\":\"2026-06-21\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalNet").value(26250))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/payouts - sellerId 누락 시 400")
    void create_validationFail() throws Exception {
        mockMvc.perform(post("/api/payouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-06-14\",\"to\":\"2026-06-21\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/payouts/{id}/pay - 지급 완료 200")
    void pay_success() throws Exception {
        given(payoutService.pay(1L)).willReturn(sample(PayoutStatus.PAID));

        mockMvc.perform(post("/api/payouts/1/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    @DisplayName("GET /api/payouts - 목록 200")
    void list_success() throws Exception {
        given(payoutService.getPayouts(any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(sample(PayoutStatus.PENDING)), 0, 20, 1, 1, false));

        mockMvc.perform(get("/api/payouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].sellerName").value("UrbanSelect"));
    }
}
