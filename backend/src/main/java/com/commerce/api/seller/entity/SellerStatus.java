package com.commerce.api.seller.entity;

/**
 * 셀러 입점 상태.
 *
 * <p>enum 값은 알파벳순(ACTIVE, SUSPENDED) — Hibernate가 생성하는 MySQL ENUM DDL과
 * Flyway 마이그레이션의 값 순서를 일치시켜 {@code ddl-auto: validate}를 통과시키기 위함.
 */
public enum SellerStatus {
    ACTIVE,     // 정상 입점(판매·정산 대상)
    SUSPENDED   // 입점 정지
}
