package com.commerce.api.coupon.entity;

/**
 * 발급된 회원 쿠폰의 사용 상태.
 *
 * <p>enum 값 순서는 MySQL ENUM DDL과 일치(알파벳순 UNUSED, USED) → ddl-auto: validate 통과.
 */
public enum MemberCouponStatus {
    UNUSED,   // 미사용(사용 가능)
    USED      // 사용 완료(단일 사용 — 다시 못 씀)
}
