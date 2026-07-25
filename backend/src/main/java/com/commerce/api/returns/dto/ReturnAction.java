package com.commerce.api.returns.dto;

/**
 * 반품/교환 처리 액션(셀러/ADMIN, #3) — 상태 전이를 요청 표면에서 표현.
 *
 * <p>P3(돈·재고 이동 없음): APPROVE·REJECT·PICK_UP·INSPECT.
 * REFUND(반품 환불+재입고)는 P4, COMPLETE(교환 재출고)는 P6에서 돈/재고 경로로 배선한다.
 */
public enum ReturnAction {
    APPROVE,    // REQUESTED → APPROVED
    REJECT,     // → REJECTED (요청 거부 / 검수 불합격)
    PICK_UP,    // APPROVED → PICKED_UP
    INSPECT,    // PICKED_UP → INSPECTED
    REFUND,     // INSPECTED → REFUNDED (RETURN, P4)
    COMPLETE    // INSPECTED → COMPLETED (EXCHANGE 재출고, P6)
}
