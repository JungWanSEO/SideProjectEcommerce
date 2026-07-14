package com.commerce.api.audit.service;

import com.commerce.api.audit.dto.AuditLogResponse;
import java.io.IOException;
import java.io.Writer;
import java.time.format.DateTimeFormatter;

/**
 * 감사 로그 → CSV 직렬화 (RFC 4180).
 *
 * <p>순수 함수라 DB 없이 단위 테스트로 이스케이프 규칙을 못 박는다.
 *
 * <p><b>Excel 함정 2가지</b>를 여기서 처리한다:
 * <ul>
 *   <li><b>UTF-8 BOM</b>: BOM이 없으면 엑셀이 CSV를 시스템 기본 인코딩(한국 윈도우=CP949)으로 읽어 한글이 깨진다.
 *       파일 맨 앞에 {@code ﻿}를 한 번 쓴다.
 *   <li><b>구분자·따옴표·줄바꿈</b>: 값에 {@code , " \n \r}가 있으면 큰따옴표로 감싸고 내부 {@code "}는 {@code ""}로
 *       이스케이프한다(감사 로그의 detail엔 URL·메시지가 들어와 콤마가 흔하다).
 * </ul>
 */
final class AuditCsvWriter {

    /** 엑셀이 UTF-8로 읽게 하는 바이트 순서 표식. */
    static final String BOM = "﻿";

    static final String HEADER = "ID,시각,행위자ID,행위자,액션,대상종류,대상ID,결과,상세";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String NEWLINE = "\r\n";   // RFC 4180

    private AuditCsvWriter() {
    }

    /** BOM + 헤더 행. */
    static void writeHeader(Writer writer) throws IOException {
        writer.write(BOM);
        writer.write(HEADER);
        writer.write(NEWLINE);
    }

    /** 감사 로그 1건을 CSV 한 행으로. */
    static void writeRow(Writer writer, AuditLogResponse log) throws IOException {
        writer.write(String.join(",",
                escape(String.valueOf(log.id())),
                escape(log.createdAt() == null ? "" : log.createdAt().format(TIMESTAMP)),
                escape(log.actorMemberId() == null ? "" : String.valueOf(log.actorMemberId())),
                escape(log.actorEmail()),
                escape(log.action()),
                escape(log.targetType()),
                escape(log.targetId()),
                escape(log.result() == null ? "" : log.result().name()),
                escape(log.detail())));
        writer.write(NEWLINE);
    }

    /** 잘렸을 때 남기는 안내 행 — "다 받았다"고 오해하지 않도록 파일 안에 명시한다(무언의 절단 금지). */
    static void writeTruncationNotice(Writer writer, int maxRows) throws IOException {
        writer.write(escape("※ 최대 " + maxRows + "행까지만 내보냈습니다. 기간·필터를 좁혀 다시 받으세요."));
        writer.write(NEWLINE);
    }

    /** RFC 4180 이스케이프. null은 빈 칸. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
