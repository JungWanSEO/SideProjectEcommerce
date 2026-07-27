package com.commerce.api.notification.handler;

import com.commerce.api.global.outbox.OutboxEvent;
import com.commerce.api.global.outbox.OutboxEventHandler;
import com.commerce.api.notification.entity.NotificationCategory;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.event.PaymentCompletedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PAYMENT_COMPLETED의 <b>두 번째 소비자</b>(#6 P3) — 결제 완료를 그 주문의 <b>셀러들</b>에게 "새 주문 인입"으로 알린다.
 * 셀러는 이 알림을 출고 시작 트리거로 쓴다(마켓플레이스 필수 — 없으면 셀러가 주문을 모른다).
 *
 * <p><b>fan-out</b>: 하나의 이벤트(결제 1건)가 주문에 담긴 <b>여러 셀러</b>에게 각각 알림을 만든다(1 이벤트 → N 셀러).
 * 이것이 P1에서 멱등 키를 (event_id, recipient_type, recipient_id) <b>복합</b>으로 바꾼 이유다 — event_id 단독
 * UNIQUE였다면 둘째 셀러 INSERT가 막혔을 것이다. {@link PaymentCompletedHandler}(구매자)와 <b>같은 이벤트 타입</b>을
 * 구독하는 별도 핸들러로, EventDispatcher가 둘 다 실행한다(한 이벤트 → 구매자 1 + 셀러 N 알림).
 *
 * <p>플랫폼 직매입(sellerId=null) 항목은 알림 대상 셀러가 없으므로 제외한다. 항목별 멱등 체크로 재도착·부분 재처리에 안전.
 */
@Component
@RequiredArgsConstructor
public class SellerNewOrderHandler implements OutboxEventHandler {

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
        Order order = orderRepository.findById(payload.orderId()).orElse(null);
        if (order == null) {
            return;
        }
        List<Long> sellerIds = order.getOrderItems().stream()
                .filter(OrderItem::isActive)
                .map(OrderItem::getSellerId)
                .filter(Objects::nonNull)   // 플랫폼 직매입(null)은 알림 대상 셀러 없음 → 제외
                .distinct()
                .toList();
        for (Long sellerId : sellerIds) {
            if (notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                    event.getId(), RecipientType.SELLER, sellerId)) {
                continue; // 이 셀러에게는 이미 만들었음(멱등)
            }
            notificationRepository.save(NotificationLog.of(
                    event.getId(), RecipientType.SELLER, sellerId,
                    event.getEventType(), NotificationCategory.TRANSACTIONAL,
                    "새 주문이 들어왔습니다 — 주문 #" + payload.orderId(), "/seller/orders"));
        }
    }

    private PaymentCompletedPayload parse(String json) {
        try {
            return objectMapper.readValue(json, PaymentCompletedPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 역직렬화 실패", e);
        }
    }
}
