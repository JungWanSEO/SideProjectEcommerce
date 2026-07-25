package com.commerce.api.order.entity;

/**
 * 배송 종류 — 원배송인지 교환 재출고인지(#3 교환).
 *
 * <p>교환(EXCHANGE) 재출고 shipment는 주문 상태 rollup·항목 배송 판정·ADMIN 일괄 전진에서 <b>제외</b>된다
 * ({@link Order}가 kind==ORIGINAL만 본다) — 안 그러면 이미 DELIVERED된 주문이 교환 재출고 때문에 SHIPPING으로
 * 후퇴해 PURCHASED 리더(리뷰자격·추천)가 오염된다. 재출고건은 shipmentId 직접 경로로만 전이한다.
 *
 * <p>enum 값은 알파벳순(EXCHANGE, ORIGINAL) — Hibernate ENUM DDL ↔ Flyway 일치(validate).
 */
public enum ShipmentKind {
    EXCHANGE,   // 교환 대체품 재출고
    ORIGINAL    // 결제 시 팬아웃된 원배송(기본)
}
