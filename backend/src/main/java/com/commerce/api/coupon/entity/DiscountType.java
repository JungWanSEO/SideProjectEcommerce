package com.commerce.api.coupon.entity;

/**
 * 할인 종류 — 할인액을 계산하는 방식(전략).
 *
 * <p>enum이 계산 로직을 직접 들고 있다(상수별 메서드 구현). 새 할인 방식이 생기면 여기에 한 상수만
 * 추가하면 돼 if/switch 분기가 흩어지지 않는다. (.NET 비유: 동작을 가진 판별 합집합/전략 enum.)
 *
 * <p>enum 값 순서는 Java 선언 순서 = MySQL ENUM DDL 순서와 일치해야 {@code ddl-auto: validate}를
 * 통과한다(Flyway V25). 알파벳순(FIXED_AMOUNT, PERCENTAGE) 유지.
 */
public enum DiscountType {

    /** 정액 할인 — 고정 금액(원)을 깎는다. discountValue = 깎을 금액(원). */
    FIXED_AMOUNT {
        @Override
        public long discountFor(long applicableAmount, long discountValue, Long maxDiscountAmount) {
            // 적용 대상 금액을 넘겨 깎으면 음수 결제가 되므로 상한을 applicableAmount로 둔다.
            return Math.min(discountValue, applicableAmount);
        }
    },

    /** 정률 할인 — 적용 대상 금액의 N%를 깎는다. discountValue = 퍼센트(1~100), maxDiscountAmount = 상한(원, 선택). */
    PERCENTAGE {
        @Override
        public long discountFor(long applicableAmount, long discountValue, Long maxDiscountAmount) {
            long raw = applicableAmount * discountValue / 100;   // long 나눗셈 = 원 단위 내림
            if (maxDiscountAmount != null) {
                raw = Math.min(raw, maxDiscountAmount);           // 정률 상한(예: "최대 1만원 할인")
            }
            return Math.min(raw, applicableAmount);               // 안전장치: 대상 금액 초과 불가
        }
    };

    /**
     * 적용 대상 금액(applicableAmount)에 대해 깎을 금액(원)을 계산한다.
     * 반환값은 항상 0 이상이며 applicableAmount를 넘지 않는다(음수 결제 방지).
     */
    public abstract long discountFor(long applicableAmount, long discountValue, Long maxDiscountAmount);
}
