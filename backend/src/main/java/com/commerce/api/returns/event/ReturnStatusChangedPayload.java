package com.commerce.api.returns.event;

/**
 * 반품/교환 상태 변경 이벤트 페이로드(#6 P2/P3) — 구매자·셀러 알림용.
 *
 * <p>{@code status}=전이 후 ReturnStatus 이름(REQUESTED/APPROVED/REJECTED/PICKED_UP/INSPECTED/REFUNDED/COMPLETED),
 * {@code type}=RETURN/EXCHANGE. 수신자 후보 둘을 모두 싣는다: {@code buyerId}(=order.memberId, 진행 알림 구매자용) ·
 * {@code sellerId}(요청 접수 알림 셀러용, P3b). 각 핸들러가 관심 있는 상태·수신자만 골라 처리한다.
 */
public record ReturnStatusChangedPayload(
        Long returnId,
        Long orderId,
        Long buyerId,
        Long sellerId,
        String status,
        String type
) {
}
