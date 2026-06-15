package com.commerce.api.coupon.entity;

/**
 * 쿠폰 상태.
 *
 * <p>유효기간(valid_from/until)과 별개의 운영 스위치다 — 기간이 남았어도 운영자가 즉시 내릴 수 있다.
 * (만료는 기간으로 판단하므로 별도 EXPIRED 값을 두지 않고 ACTIVE/DISABLED 2값만 둔다.)
 * enum 값 순서는 MySQL ENUM DDL과 일치(알파벳순 ACTIVE, DISABLED) → validate 통과.
 */
public enum CouponStatus {
    ACTIVE,     // 사용 가능
    DISABLED    // 운영자가 비활성화(사용 불가)
}
