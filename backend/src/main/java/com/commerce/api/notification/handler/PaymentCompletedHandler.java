package com.commerce.api.notification.handler;

import com.commerce.api.global.outbox.OutboxEvent;
import com.commerce.api.global.outbox.OutboxEventHandler;
import com.commerce.api.notification.entity.NotificationCategory;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.event.PaymentCompletedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PAYMENT_COMPLETED 이벤트 소비자 — 결제 완료를 <b>구매자 인박스</b>에 알림으로 남긴다(#6).
 *
 * <p>수신자 해석: 이벤트 페이로드는 orderId만 실으므로, 주문을 조회해 {@code order.memberId}를 구매자로 삼는다.
 *
 * <p><b>멱등</b>: at-least-once 발행이라 같은 이벤트가 두 번 올 수 있다. (event_id, recipient)로 먼저 걸러내고,
 * DB의 복합 UNIQUE가 최후 방어선이 된다(대사 예외 큐와 같은 "중복은 정상 시나리오" 사고).
 */
@Component
@RequiredArgsConstructor
public class PaymentCompletedHandler implements OutboxEventHandler {

    private final NotificationRepository notificationRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "PAYMENT_COMPLETED";
    }

    @Override
    public void handle(OutboxEvent event) {
        PaymentCompletedPayload payload = parse(event.getPayload());
        Long buyerId = orderRepository.findById(payload.orderId())
                .map(Order::getMemberId)
                .orElse(null);
        if (buyerId == null) {
            return; // 주문을 못 찾는 이례적 상황 — 수신자를 못 정하므로 알림 생략
        }
        if (notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                event.getId(), RecipientType.BUYER, buyerId)) {
            return; // 이미 처리한 이벤트 → 스킵(멱등)
        }
        String message = "결제 완료 — 주문 #" + payload.orderId() + " · " + payload.amount() + "원";
        notificationRepository.save(NotificationLog.of(
                event.getId(), RecipientType.BUYER, buyerId,
                event.getEventType(), NotificationCategory.TRANSACTIONAL,
                message, "/orders/" + payload.orderId()));
    }

    private PaymentCompletedPayload parse(String json) {
        try {
            return objectMapper.readValue(json, PaymentCompletedPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 역직렬화 실패", e);
        }
    }
}
