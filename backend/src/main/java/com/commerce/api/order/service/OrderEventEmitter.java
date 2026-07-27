package com.commerce.api.order.service;

import com.commerce.api.global.outbox.OutboxService;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.event.OrderStatusChangedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 주문 상태 변경 → 구매자 알림 이벤트 발행(#6 P2). 아웃박스에 기록하며, {@link OutboxService#append}가
 * <b>호출자의 트랜잭션에 합류</b>하므로 상태 변경과 이벤트 INSERT가 한 커밋이 된다(원자성 — 아웃박스의 핵심).
 *
 * <p>배송 전진·취소 등 여러 경로가 주문 status를 바꾸므로, 각 경로가 "전이 전 status"를 넘겨주면
 * 여기서 <b>관심 상태로 "새로" 바뀌었을 때만</b> 이벤트를 낸다(불변이거나 관심 밖이면 no-op → 알림 스팸 방지).
 * 예: 멀티셀러 주문에서 둘째 shipment가 SHIPPING이 돼도 주문 status는 이미 SHIPPING이라 추가 알림이 안 나간다.
 */
@Component
@RequiredArgsConstructor
public class OrderEventEmitter {

    /** 구매자에게 알릴 주문 상태(도달 시 1회 알림). */
    private static final Set<OrderStatus> NOTIFIABLE =
            Set.of(OrderStatus.SHIPPING, OrderStatus.DELIVERED, OrderStatus.CANCELLED);

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /** 주문 status가 {@code before}에서 관심 상태로 새로 바뀌었으면 ORDER_STATUS_CHANGED 이벤트를 낸다(아니면 no-op). */
    public void emitIfStatusBecameNotifiable(Order order, OrderStatus before) {
        OrderStatus after = order.getStatus();
        if (after == before || !NOTIFIABLE.contains(after)) {
            return;
        }
        outboxService.append(
                "ORDER_STATUS_CHANGED",
                "ORDER",
                String.valueOf(order.getId()),
                toJson(new OrderStatusChangedPayload(order.getId(), order.getMemberId(), after.name())));
    }

    private String toJson(OrderStatusChangedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 직렬화 실패", e);
        }
    }
}
