package com.commerce.api.order.entity;

/**
 * 배송(shipment) 상태 — 셀러별 출고 추적축(#1 멀티셀러 상태 단위 c안).
 *
 * <p>한 주문(order)에 셀러가 여러 명 섞일 수 있어, 배송 진행을 <b>주문 전체</b>가 아니라 셀러별 shipment 단위로 내린다.
 * 각 shipment는 자기 상태를 <b>forward-only</b>로 전진: PAID → SHIPPING → DELIVERED (건너뛰기·되돌리기 금지,
 * {@link Shipment#advanceShipping} 강제). {@link Order#getStatus()}는 이 shipment들의 rollup으로 재계산되는 파생값이다.
 *
 * <p>{@link OrderStatus}와 값 집합이 같지만(취소/배송) 별도 enum으로 둔다 — Order enum을 재사용하면 미사용 PENDING까지
 * DDL에 끌려와 지저분해지고, rollup은 어차피 shipment→order 상태 매핑이 필요하기 때문. enum 값은 알파벳순
 * (CANCELLED, DELIVERED, PAID, SHIPPING) — Hibernate ENUM DDL ↔ Flyway 일치(validate, V4·V34·V39 컨벤션).
 */
public enum ShipmentStatus {
    CANCELLED,   // 이 셀러 항목이 전부 취소됨(출고 전까지만 가능)
    DELIVERED,   // 배송 완료(종료)
    PAID,        // 결제 완료·출고 대기(shipment 생성 시점 상태)
    SHIPPING     // 배송 중(셀러 또는 ADMIN이 PAID → SHIPPING으로 전진)
}
