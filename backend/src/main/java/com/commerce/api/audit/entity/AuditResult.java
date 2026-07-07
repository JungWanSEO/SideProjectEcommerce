package com.commerce.api.audit.entity;

/**
 * 감사 로그 결과 — 어드민 변경이 성공했는지 실패(예외)했는지.
 * 실패도 기록해 "누가 무엇을 시도했다 실패"까지 추적한다.
 */
public enum AuditResult {
    SUCCESS,
    FAILURE
}
