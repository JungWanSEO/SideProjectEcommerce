package com.commerce.api.settlement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.settlement.dto.MismatchResponse;
import com.commerce.api.settlement.dto.ReconciliationResult;
import com.commerce.api.settlement.entity.MismatchStatus;
import com.commerce.api.settlement.entity.MismatchType;
import com.commerce.api.settlement.service.ReconciliationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ReconciliationController 테스트 (@WebMvcTest) — 대사 HTTP 경계 (JaCoCo에서 0%로 드러난 구멍).
 * 대사 실행(일자 윈도우 바인딩)·불일치 목록·해소/무시의 상태코드와 응답 계약을 못 박는다.
 */
@WebMvcTest(ReconciliationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReconciliationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ReconciliationService reconciliationService;

    private MismatchResponse mismatch(MismatchStatus status, String note) {
        return new MismatchResponse(1L, "tx-1", "TOSS", MismatchType.AMOUNT_MISMATCH,
                10_000L, 9_000L, "금액 상이", status, note, LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/reconciliations/run - 대사 실행 201 + from/to(정산일 윈도우)가 바인딩된다")
    void run_created_withWindow() throws Exception {
        given(reconciliationService.reconcile(any(), any())).willReturn(
                new ReconciliationResult(10, 1, 0, 2, 0, 3, 1, List.of()));

        mockMvc.perform(post("/api/reconciliations/run")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.matched").value(10))
                .andExpect(jsonPath("$.data.totalMismatches").value(3))
                .andExpect(jsonPath("$.data.alreadyHandled").value(1));

        verify(reconciliationService).reconcile(
                eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)));
    }

    @Test
    @DisplayName("POST /api/reconciliations/run - 인자가 없으면 전체 대사(from·to 둘 다 null)")
    void run_withoutWindow() throws Exception {
        given(reconciliationService.reconcile(any(), any()))
                .willReturn(new ReconciliationResult(0, 0, 0, 0, 0, 0, 0, List.of()));

        mockMvc.perform(post("/api/reconciliations/run"))
                .andExpect(status().isCreated());

        verify(reconciliationService).reconcile(isNull(), isNull());
    }

    @Test
    @DisplayName("GET /api/reconciliations/mismatches - 불일치 목록 200(status·provider 필터)")
    void getMismatches_success() throws Exception {
        given(reconciliationService.getMismatches(
                eq(MismatchStatus.OPEN), eq("TOSS"), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(mismatch(MismatchStatus.OPEN, null)),
                        0, 20, 1L, 1, false));

        mockMvc.perform(get("/api/reconciliations/mismatches")
                        .param("status", "OPEN")
                        .param("provider", "TOSS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].type").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.data.content[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("POST /api/reconciliations/mismatches/{id}/resolve - 처리 200(사유 전달)")
    void resolve_success() throws Exception {
        given(reconciliationService.resolve(1L, "PG 재전송으로 보정"))
                .willReturn(mismatch(MismatchStatus.RESOLVED, "PG 재전송으로 보정"));

        mockMvc.perform(post("/api/reconciliations/mismatches/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"PG 재전송으로 보정"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolutionNote").value("PG 재전송으로 보정"));
    }

    @Test
    @DisplayName("POST /api/reconciliations/mismatches/{id}/ignore - 본문 없이도 무시 처리 200(사유 null)")
    void ignore_withoutBody() throws Exception {
        given(reconciliationService.ignore(1L, null)).willReturn(mismatch(MismatchStatus.IGNORED, null));

        mockMvc.perform(post("/api/reconciliations/mismatches/1/ignore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IGNORED"));
    }

    @Test
    @DisplayName("POST /api/reconciliations/mismatches/{id}/resolve - 이미 종료된 불일치면 409")
    void resolve_conflict() throws Exception {
        given(reconciliationService.resolve(eq(1L), any()))
                .willThrow(new BusinessException(HttpStatus.CONFLICT, "이미 처리된 불일치입니다."));

        mockMvc.perform(post("/api/reconciliations/mismatches/1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"중복 처리"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }
}
