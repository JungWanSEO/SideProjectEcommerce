package com.commerce.api.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.audit.dto.AuditLogResponse;
import com.commerce.api.audit.entity.AuditResult;
import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 직렬화 규칙 단위 테스트 (DB 없음).
 * 엑셀 한글 깨짐(BOM)과 구분자·따옴표·줄바꿈 이스케이프(RFC 4180)를 못 박는다.
 */
class AuditCsvWriterTest {

    @Test
    @DisplayName("헤더 - UTF-8 BOM으로 시작한다(없으면 엑셀이 CP949로 읽어 한글이 깨진다)")
    void writeHeader_startsWithBom() throws IOException {
        StringWriter out = new StringWriter();

        AuditCsvWriter.writeHeader(out);

        assertThat(out.toString()).startsWith("﻿");
        assertThat(out.toString()).contains("ID,시각,행위자ID,행위자,액션,대상종류,대상ID,결과,상세");
        assertThat(out.toString()).endsWith("\r\n");   // RFC 4180 줄바꿈
    }

    @Test
    @DisplayName("행 - 콤마·따옴표·줄바꿈이 든 값은 따옴표로 감싸고 내부 따옴표는 두 번으로 이스케이프")
    void writeRow_escapes() throws IOException {
        StringWriter out = new StringWriter();
        AuditLogResponse log = new AuditLogResponse(
                7L, 1L, "admin@commerce.com", "PRODUCT_UPDATE", "PRODUCT", "42",
                "PUT /api/products/42, name=\"봄 니트\"\n두번째 줄",
                AuditResult.SUCCESS, LocalDateTime.of(2026, 7, 14, 9, 30, 0));

        AuditCsvWriter.writeRow(out, log);

        assertThat(out.toString()).isEqualTo(
                "7,2026-07-14 09:30:00,1,admin@commerce.com,PRODUCT_UPDATE,PRODUCT,42,SUCCESS,"
                        + "\"PUT /api/products/42, name=\"\"봄 니트\"\"\n두번째 줄\"\r\n");
    }

    @Test
    @DisplayName("행 - null 필드는 빈 칸(시스템 행위자·대상 없음)")
    void writeRow_nullsBecomeEmpty() throws IOException {
        StringWriter out = new StringWriter();
        AuditLogResponse log = new AuditLogResponse(
                8L, null, null, "SETTLEMENT_RUN", null, null, null,
                AuditResult.FAILURE, LocalDateTime.of(2026, 7, 14, 10, 0, 0));

        AuditCsvWriter.writeRow(out, log);

        assertThat(out.toString()).isEqualTo("8,2026-07-14 10:00:00,,,SETTLEMENT_RUN,,,FAILURE,\r\n");
    }

    @Test
    @DisplayName("절단 안내 - 상한에 걸리면 파일 안에 남긴다(무언의 절단 금지)")
    void writeTruncationNotice() throws IOException {
        StringWriter out = new StringWriter();

        AuditCsvWriter.writeTruncationNotice(out, 50000);

        assertThat(out.toString()).contains("최대 50000행까지만 내보냈습니다");
    }
}
