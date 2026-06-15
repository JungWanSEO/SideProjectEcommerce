package com.commerce.api.coupon.entity;

/**
 * 쿠폰 배포 방식 — 코드를 누구나 쓰는 공개형인지, 회원에게 발급돼 지갑에서 쓰는 발급형인지.
 *
 * <ul>
 *   <li>{@code ISSUED} — 발급형: ADMIN이 회원에게 발급해야 쓸 수 있다(쿠폰함). 회원·쿠폰당 1장, <b>단일 사용</b>.</li>
 *   <li>{@code PUBLIC} — 공개형: 코드만 알면 누구나 입력해 적용(프로모코드). 회원 발급·사용 추적 없음.</li>
 * </ul>
 *
 * <p>enum 값 순서는 MySQL ENUM DDL과 일치(알파벳순 ISSUED, PUBLIC) → ddl-auto: validate 통과.
 */
public enum CouponIssueType {
    ISSUED,   // 발급형(지갑·단일사용)
    PUBLIC    // 공개형(코드 입력·무제한)
}
