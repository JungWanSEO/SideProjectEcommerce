package com.commerce.api.returns.event;

/**
 * 반품/교환 상태 변경 이벤트 페이로드(#6 P2) — 구매자 알림용.
 *
 * <p>{@code status}=전이 후 ReturnStatus 이름(APPROVED/REJECTED/PICKED_UP/INSPECTED/REFUNDED/COMPLETED),
 * {@code type}=RETURN/EXCHANGE(문구 분기용). 수신자는 {@code buyerId}(반품 소유 구매자 = order.memberId).
 */
public record ReturnStatusChangedPayload(
        Long returnId,
        Long orderId,
        Long buyerId,
        String status,
        String type
) {
}
