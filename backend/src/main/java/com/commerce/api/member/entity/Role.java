package com.commerce.api.member.entity;

/**
 * 회원 권한.
 * Spring Security 권한 문자열은 "ROLE_" 접두사 규칙을 따른다 → ROLE_USER / ROLE_ADMIN / ROLE_SELLER.
 *
 * <p>enum 값은 알파벳순(ADMIN, SELLER, USER) — Hibernate ENUM DDL ↔ Flyway 일치(validate).
 */
public enum Role {
    ADMIN,    // 운영자
    SELLER,   // 입점 셀러 운영자(자기 정산만 조회) — Member.sellerId로 셀러에 연결
    USER      // 일반 구매자
}
