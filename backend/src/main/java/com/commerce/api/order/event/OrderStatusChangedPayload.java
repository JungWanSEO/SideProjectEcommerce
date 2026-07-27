package com.commerce.api.order.event;

/**
 * 주문 상태 변경 이벤트 페이로드(#6 P2) — 구매자 알림용.
 *
 * <p>{@code status}는 알림 대상 전이(SHIPPING/DELIVERED/CANCELLED)의 이름. 수신자는 {@code buyerId}(order.memberId).
 * 이벤트가 수신자·상태를 스스로 담아, 소비 핸들러가 주문을 되조회할 필요 없이 알림을 만든다.
 */
public record OrderStatusChangedPayload(
        Long orderId,
        Long buyerId,
        String status
) {
}
