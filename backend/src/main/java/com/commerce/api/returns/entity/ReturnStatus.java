package com.commerce.api.returns.entity;

/**
 * 반품/교환 요청의 진행 상태(#3) — 배송완료 후 역방향 다단계 워크플로.
 *
 * <p>전이(엔티티가 강제, {@link ReturnRequest}):
 * <pre>
 *   공통:   REQUESTED → APPROVED | REJECTED
 *           APPROVED  → PICKED_UP
 *           PICKED_UP → INSPECTED
 *           INSPECTED → REJECTED            (검수 불합격)
 *   RETURN:   INSPECTED → REFUNDED          (환불+재입고, 종료)
 *   EXCHANGE: INSPECTED → COMPLETED         (대체품 재출고, 종료)
 * </pre>
 *
 * <p>enum 값은 알파벳순 — Hibernate ENUM DDL ↔ Flyway 일치(validate, V4·V45 컨벤션).
 */
public enum ReturnStatus {
    APPROVED,    // 셀러/ADMIN 승인 — 수거 대기
    COMPLETED,   // 교환 완료(대체품 재출고됨, 종료)
    INSPECTED,   // 반송품 검수 통과 — 환불/재출고 대기
    PICKED_UP,   // 반송품 수거됨 — 검수 대기
    REFUNDED,    // 반품 환불+재입고 완료(종료)
    REJECTED,    // 요청 거부 또는 검수 불합격(종료, 금액·재고 무영향)
    REQUESTED    // 구매자 반품/교환 요청(시작)
}
