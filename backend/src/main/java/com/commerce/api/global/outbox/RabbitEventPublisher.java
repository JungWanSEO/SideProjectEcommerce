package com.commerce.api.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 이벤트 발행 어댑터 — {@code outbox.publisher=rabbit}일 때 활성(기본은 {@link InProcessEventPublisher}).
 *
 * <p>아웃박스 폴러가 호출하면 이벤트를 {@link OutboxMessage}(JSON)로 변환해 토픽 익스체인지로 보낸다
 * (라우팅 키 = eventType). "발행 = 브로커에 넣음"까지가 책임이고(at-least-once), 소비는
 * {@link OutboxEventConsumer}가 비동기로 분리 처리한다 — 진짜 디커플링.
 *
 * <p>전송 후 폴러가 outbox 행을 PUBLISHED로 표시한다. 표시 전 크래시하면 다음 tick에 재전송되므로
 * (at-least-once) 소비자는 멱등해야 한다(NotificationLog.event_id UNIQUE).
 */
@Component
@ConditionalOnProperty(name = "outbox.publisher", havingValue = "rabbit")
@RequiredArgsConstructor
public class RabbitEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(OutboxEvent event) {
        // 라우팅 키 = eventType → 익스체인지가 그 키에 바인딩된 큐로 전달(예: PAYMENT_COMPLETED → 알림 큐).
        rabbitTemplate.convertAndSend(
                RabbitConfig.EVENTS_EXCHANGE, event.getEventType(), OutboxMessage.from(event));
    }
}
