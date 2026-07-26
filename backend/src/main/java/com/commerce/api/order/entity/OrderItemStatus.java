package com.commerce.api.order.entity;

/**
 * 주문 항목 상태 — 부분환불(항목 단위 취소)·반품 지원용.
 *
 * <p>enum 값은 알파벳순(ACTIVE, CANCELLED, RETURNED) — Hibernate ENUM DDL ↔ Flyway 일치(validate).
 * 정산/결제/재고가 읽는 "이 항목 유효한가"의 단일 출처: isActive()==ACTIVE만 참이라 CANCELLED·RETURNED는 자동 비활성
 * (정산 reverseRefunds가 status != ACTIVE를 자동 상계). 취소(출고 전)와 반품(배송완료 후)을 <b>원장에서 구분</b>하려
 * 별도 상태값으로 둔다(감사·리포팅).
 */
public enum OrderItemStatus {
    ACTIVE,      // 유효한 주문 항목
    CANCELLED,   // 출고 전 취소된 항목(부분환불·재고 복원·정산 상계)
    RETURNED     // 배송완료 후 반품 확정된 항목(#3, 환불·재입고·정산 상계 — 취소와 원장 구분)
}
