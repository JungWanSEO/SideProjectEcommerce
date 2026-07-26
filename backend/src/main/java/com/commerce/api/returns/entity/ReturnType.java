package com.commerce.api.returns.entity;

/**
 * 반품 요청 종류(#3) — 환불받는 반품인지, 대체품으로 바꾸는 교환인지.
 *
 * <p>enum 값은 알파벳순(EXCHANGE, RETURN) — Hibernate ENUM DDL ↔ Flyway 일치(validate).
 */
public enum ReturnType {
    EXCHANGE,   // 교환: 대체품(같은 상품 다른 옵션) 재출고 — 환불 없음(v1 동일가)
    RETURN      // 반품: 환불 + 재입고
}
