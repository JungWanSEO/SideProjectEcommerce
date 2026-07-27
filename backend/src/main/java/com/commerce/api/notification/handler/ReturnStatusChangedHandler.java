package com.commerce.api.notification.handler;

import com.commerce.api.global.outbox.OutboxEvent;
import com.commerce.api.global.outbox.OutboxEventHandler;
import com.commerce.api.notification.entity.NotificationCategory;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import com.commerce.api.returns.event.ReturnStatusChangedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * RETURN_STATUS_CHANGED 이벤트 소비자 — 반품/교환 진행을 <b>구매자 인박스</b>에 남긴다(#6 P2).
 *
 * <p>상태→문구 매핑으로 승인·거부·수거·검수·환불·교환완료를 한 핸들러가 처리한다. 매핑에 없는 상태는 무시(방어).
 */
@Component
@RequiredArgsConstructor
public class ReturnStatusChangedHandler implements OutboxEventHandler {

    private static final Map<String, String> MESSAGES = Map.of(
            "APPROVED", "반품/교환 요청이 승인되었습니다",
            "REJECTED", "반품/교환 요청이 거부되었습니다",
            "PICKED_UP", "반송품이 수거되었습니다",
            "INSPECTED", "반송품 검수가 완료되었습니다",
            "REFUNDED", "반품 환불이 완료되었습니다",
            "COMPLETED", "교환이 완료되었습니다");

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "RETURN_STATUS_CHANGED";
    }

    @Override
    public void handle(OutboxEvent event) {
        ReturnStatusChangedPayload payload = parse(event.getPayload());
        String label = MESSAGES.get(payload.status());
        if (label == null || payload.buyerId() == null) {
            return; // 관심 밖 상태이거나 수신자 미상 → 생략
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

    private ReturnStatusChangedPayload parse(String json) {
        try {
            return objectMapper.readValue(json, ReturnStatusChangedPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 역직렬화 실패", e);
        }
    }
}
