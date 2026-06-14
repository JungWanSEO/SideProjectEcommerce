package com.commerce.api.settlement.entity;

/**
 * 셀러 지급 묶음(Payout) 상태.
 *
 * <p>enum 값은 알파벳순(PAID, PENDING) — Hibernate ENUM DDL ↔ Flyway 일치(validate).
 */
public enum PayoutStatus {
    PAID,      // 지급 완료
    PENDING    // 지급 대기(묶음 생성됨, 아직 미지급)
}
