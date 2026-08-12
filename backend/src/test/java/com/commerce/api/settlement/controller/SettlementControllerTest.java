package com.commerce.api.settlement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.dto.SettlementReverseResponse;
import com.commerce.api.settlement.dto.SettlementRunResponse;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.service.SettlementService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SettlementController 테스트 (@WebMvcTest) — 정산 HTTP 경계.
 *
 * <p><b>왜 이제야</b>: JaCoCo(07-14)에서 이 컨트롤러가 <b>0%</b>로 드러났다 — 서비스(SettlementService)는
 * 91.9% 덮여 있는데 정작 <b>배치 실행·입금 처리의 HTTP 경계</b>(상태코드·바인딩·응답 모양)는 아무도 안 쳤다.
 * 돈을 움직이는 엔드포인트라 계약이 조용히 바뀌면 어드민 콘솔이 깨진다.
 *
 * <p>인가(ADMIN)는 SecurityConfig 매처의 몫이라 여기선 필터를 끄고(addFilters=false) 컨트롤러 로직만 본다.
 */
@WebMvcTest(SettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettlementControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SettlementService settlementService;

    private SettlementResponse entry(Long id, SettlementStatus status) {
        return new SettlementResponse(id, 10L, 20L, "tx-1", "TOSS", 5L,
                10_000L, 250L, 0.025, 1_000L, 0.10, 0L, null, 8_750L,
                com.commerce.api.settlement.entity.SettlementEntryKind.SALE, 0L,
                status, LocalDate.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/settlements/run - 배치 실행 201 + 집계 요약")
    void run_created() throws Exception {
        given(settlementService.run()).willReturn(new SettlementRunResponse(
                2, 20_000L, 500L, 2_000L, 17_500L, 0L, List.of(), List.of()));

        mockMvc.perform(post("/api/settlements/run"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.createdCount").value(2))
                .andExpect(jsonPath("$.data.totalNetAmount").value(17500));
    }

    @Test
    @DisplayName("GET /api/settlements - 목록 200 + 셀러·상태·기간 필터가 조건으로 바인딩된다")
    void getSettlements_bindsCondition() throws Exception {
        given(settlementService.getSettlements(any(SettlementSearchCondition.class), any(Pageable.class)))
                .willReturn(new PageResponse<>(
                        List.of(entry(1L, SettlementStatus.SCHEDULED)), 0, 20, 1L, 1, false));

        mockMvc.perform(get("/api/settlements")
                        .param("sellerId", "5")
                        .param("status", "SCHEDULED")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].netAmount").value(8750))
                .andExpect(jsonPath("$.data.content[0].status").value("SCHEDULED"));

        ArgumentCaptor<SettlementSearchCondition> captor =
                ArgumentCaptor.forClass(SettlementSearchCondition.class);
        verify(settlementService).getSettlements(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue().sellerId()).isEqualTo(5L);
        assertThat(captor.getValue().status()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().to()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("POST /api/settlements/reverse-refunds - 환불 상계(역분개) 201")
    void reverseRefunds_created() throws Exception {
        given(settlementService.reverseRefunds()).willReturn(new SettlementReverseResponse(1, -8_750L));

        mockMvc.perform(post("/api/settlements/reverse-refunds"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reversedCount").value(1))
                .andExpect(jsonPath("$.data.totalReversedNet").value(-8750));
    }

    @Test
    @DisplayName("GET /api/settlements/summary - 셀러별 집계 200(매출·수수료·실수령)")
    void sellerSummary_success() throws Exception {
        given(settlementService.getSellerSummary(any(SettlementSearchCondition.class))).willReturn(
                List.of(new SellerSettlementSummary(5L, "마루브랜드", 2, 20_000L, 500L, 2_000L, 0L, 17_500L)));

        mockMvc.perform(get("/api/settlements/summary").param("sellerId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sellerId").value(5))
                .andExpect(jsonPath("$.data[0].netAmount").value(17500));
    }

    @Test
    @DisplayName("POST /api/settlements/{id}/payout - 입금 확인 200")
    void payout_success() throws Exception {
        given(settlementService.payout(1L)).willReturn(entry(1L, SettlementStatus.PAID_OUT));

        mockMvc.perform(post("/api/settlements/1/payout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID_OUT"));
    }

    @Test
    @DisplayName("POST /api/settlements/{id}/payout - 이미 지급된 항목이면 409")
    void payout_conflict() throws Exception {
        given(settlementService.payout(1L))
                .willThrow(new BusinessException(HttpStatus.CONFLICT, "이미 입금 처리된 정산 항목입니다."));

        mockMvc.perform(post("/api/settlements/1/payout"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }
}
