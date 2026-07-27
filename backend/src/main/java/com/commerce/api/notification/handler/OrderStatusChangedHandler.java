package com.commerce.api.notification.handler;

import com.commerce.api.global.outbox.OutboxEvent;
import com.commerce.api.global.outbox.OutboxEventHandler;
import com.commerce.api.notification.entity.NotificationCategory;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import com.commerce.api.order.event.OrderStatusChangedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ORDER_STATUS_CHANGED 이벤트 소비자 — 주문 상태 변화를 <b>구매자 인박스</b>에 알림으로 남긴다(#6 P2).
 *
 * <p>상태→문구 매핑으로 여러 전이를 하나의 이벤트 타입/핸들러로 처리한다(배송 시작/완료·전체 취소).
 * 매핑에 없는 상태는 무시(방어) — 발행 측이 관심 상태만 내지만, 소비 측도 스스로 필터한다.
 */
@Component
@RequiredArgsConstructor
public class OrderStatusChangedHandler implements OutboxEventHandler {

    private static final Map<String, String> MESSAGES = Map.of(
            "SHIPPING", "배송이 시작되었습니다",
            "DELIVERED", "배송이 완료되었습니다",
            "CANCELLED", "주문이 취소되었습니다");

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "ORDER_STATUS_CHANGED";
    }

    @Override
    public void handle(OutboxEvent event) {
        OrderStatusChangedPayload payload = parse(event.getPayload());
        String label = MESSAGES.get(payload.status());
        if (label == null || payload.buyerId() == null) {
            return; // 관심 밖 상태이거나 수신자 미상 → 알림 생략
        }
        if (notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                event.getId(), RecipientType.BUYER, payload.buyerId())) {
            return; // 이미 처리(멱등)
        }
        String message = label + " — 주문 #" + payload.orderId();
        notificationRepository.save(NotificationLog.of(
                event.getId(), RecipientType.BUYER, payload.buyerId(),
                event.getEventType(), NotificationCategory.TRANSACTIONAL,
                message, "/orders/" + payload.orderId()));
    }

    private OrderStatusChangedPayload parse(String json) {
        try {
            return objectMapper.readValue(json, OrderStatusChangedPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 역직렬화 실패", e);
        }
    }
}
