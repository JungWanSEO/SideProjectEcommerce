package com.commerce.api.order.entity;

/**
 * 주문 항목 상태 — 부분환불(항목 단위 취소) 지원용.
 *
 * <p>enum 값은 알파벳순(ACTIVE, CANCELLED) — Hibernate ENUM DDL ↔ Flyway 일치(validate).
 */
public enum OrderItemStatus {
    ACTIVE,     // 유효한 주문 항목
    CANCELLED   // 부분환불로 취소된 항목(재고 복원·환불·정산 상계 대상)
}
