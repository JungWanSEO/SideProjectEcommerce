package com.commerce.api.coupon.entity;

/**
 * 할인 비용 부담 주체 — "이 쿠폰의 할인금을 누가 부담하는가".
 *
 * <p>셀러별 정산의 핵심 차별화 축이다. 같은 5,000원 할인이라도 누가 부담하느냐에 따라
 * 셀러 실수령과 플랫폼 손익이 달라진다(PG수수료 안분·플랫폼 수수료에 이은 3번째 회계 차원).
 * 실제 정산 분해 반영은 Step 2(SettlementEntry)에서 한다.
 *
 * <ul>
 *   <li>{@code PLATFORM} — 플랫폼 마케팅비. 셀러는 원가 기준으로 받고 플랫폼이 할인분을 보전(subsidy).
 *   <li>{@code SELLER}   — 셀러 자체 프로모션. 셀러 매출(gross)이 할인만큼 줄어 셀러가 부담.
 * </ul>
 *
 * <p>enum 값 순서는 MySQL ENUM DDL과 일치(알파벳순 PLATFORM, SELLER) → validate 통과.
 */
public enum CouponFundedBy {
    PLATFORM,   // 플랫폼 부담(마케팅비)
    SELLER      // 셀러 부담(자체 프로모션)
}
