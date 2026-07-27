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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * RETURN_STATUS_CHANGED의 <b>셀러용 소비자</b>(#6 P3b) — 구매자가 반품/교환을 <b>요청(REQUESTED)</b>하면
 * 그 항목의 셀러에게 "반품/교환 요청 접수"를 알린다(셀러가 승인·검수해야 진행되므로 행동 트리거).
 *
 * <p>{@link ReturnStatusChangedHandler}(구매자용)와 <b>같은 이벤트 타입</b>을 구독하되, 이 핸들러는 <b>REQUESTED만</b>
 * 처리하고 수신자를 SELLER로 라우팅한다(다른 전이는 구매자 핸들러 몫). 한 이벤트를 두 관점이 나눠 소비하는 예.
 */
@Component
@RequiredArgsConstructor
public class SellerReturnRequestedHandler implements OutboxEventHandler {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "RETURN_STATUS_CHANGED";
    }

    @Override
    public void handle(OutboxEvent event) {
        ReturnStatusChangedPayload payload = parse(event.getPayload());
        if (!"REQUESTED".equals(payload.status()) || payload.sellerId() == null) {
            return; // 요청 접수(REQUESTED)만·셀러 미상이면 생략 — 이후 전이는 구매자 핸들러가 처리
        }
        if (notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                event.getId(), RecipientType.SELLER, payload.sellerId())) {
            return; // 이미 처리(멱등)
        }
        String kind = "EXCHANGE".equals(payload.type()) ? "교환" : "반품";
        String message = kind + " 요청이 접수되었습니다 — 주문 #" + payload.orderId();
        notificationRepository.save(NotificationLog.of(
                event.getId(), RecipientType.SELLER, payload.sellerId(),
                event.getEventType(), NotificationCategory.TRANSACTIONAL,
                message, "/seller/returns"));
    }

    private ReturnStatusChangedPayload parse(String json) {
        try {
            return objectMapper.readValue(json, ReturnStatusChangedPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 역직렬화 실패", e);
        }
    }
}
