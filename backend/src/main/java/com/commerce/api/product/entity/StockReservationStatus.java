package com.commerce.api.product.entity;

/**
 * 재고 예약 상태.
 *
 * <ul>
 *   <li>{@code ACTIVE}   — 예약됨(결제 대기). reserved 카운터에 반영돼 가용재고를 줄인다.</li>
 *   <li>{@code CONSUMED} — 결제 확정으로 실재고 차감에 전환됨(예약 종료).</li>
 *   <li>{@code RELEASED} — 만료·취소로 해제됨(reserved 되돌림, 예약 종료).</li>
 * </ul>
 *
 * <p>DDL enum 값은 알파벳순(ACTIVE·CONSUMED·RELEASED)으로 고정 — @Enumerated(STRING) 저장 정합.
 */
public enum StockReservationStatus {
    ACTIVE,
    CONSUMED,
    RELEASED
}
