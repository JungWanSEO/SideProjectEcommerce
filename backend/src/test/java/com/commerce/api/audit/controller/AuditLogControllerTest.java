package com.commerce.api.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.audit.dto.AuditLogResponse;
import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.entity.AuditResult;
import com.commerce.api.audit.service.AuditLogService;
import com.commerce.api.global.common.PageResponse;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * AuditLogController 테스트 (@WebMvcTest). 목록 조회 + CSV 내보내기(스트리밍).
 * 보안 필터는 비활성(addFilters = false) — ADMIN 인가는 SecurityConfig 매처의 몫.
 */
@WebMvcTest(AuditLogController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    @DisplayName("GET /api/audit-logs - 최신순 목록 200")
    void search_success() throws Exception {
        PageResponse<AuditLogResponse> page = new PageResponse<>(
                List.of(new AuditLogResponse(1L, 9L, "admin@commerce.com", "PRODUCT_UPDATE",
                        "PRODUCT", "42", "PUT /api/products/42", AuditResult.SUCCESS, LocalDateTime.now())),
                0, 20, 1L, 1, false);
        given(auditLogService.search(any(AuditLogSearchCondition.class), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].action").value("PRODUCT_UPDATE"))
                .andExpect(jsonPath("$.data.content[0].actorEmail").value("admin@commerce.com"));
    }

    @Test
    @DisplayName("GET /api/audit-logs/export - text/csv + 첨부파일 헤더로 스트리밍한다")
    void export_streamsCsv() throws Exception {
        willAnswer(invocation -> {
            Writer writer = invocation.getArgument(1);
            writer.write("﻿ID,시각\r\n1,2026-07-14 09:00:00\r\n");   // 서비스가 흘려 쓰는 내용(BOM 포함)
            return null;
        }).given(auditLogService).exportCsv(any(AuditLogSearchCondition.class), any(Writer.class));

        // StreamingResponseBody는 비동기 응답 — 시작을 확인하고 dispatch해야 본문이 채워진다.
        MvcResult started = mockMvc.perform(get("/api/audit-logs/export").param("targetType", "PRODUCT"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment; filename=\"audit-logs-")))
                .andReturn();

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿");            // 엑셀 한글용 BOM이 그대로 나간다
        assertThat(csv).contains("1,2026-07-14 09:00:00");
    }
}
