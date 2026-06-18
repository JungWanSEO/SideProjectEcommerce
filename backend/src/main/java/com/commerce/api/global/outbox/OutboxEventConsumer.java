package com.commerce.api.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RabbitMQ 이벤트 소비자 — {@code outbox.publisher=rabbit}일 때 활성. 알림 큐를 구독해 핸들러로 디스패치한다.
 *
 * <p>발행자({@link RabbitEventPublisher})와 분리된 비동기 소비 — 발행자가 죽어도, 앱 재시작 후에도 브로커에
 * 쌓인 메시지를 처리한다. 받은 {@link OutboxMessage}를 비영속 {@link OutboxEvent}로 복원해
 * {@link EventDispatcher}가 in-process와 <b>동일한 핸들러</b>를 태운다(중복 로직 0).
 *
 * <p>{@code @Transactional}: 핸들러 부수효과(NotificationLog 저장)가 커밋되게 한다. 핸들러가 던지면 롤백되고
 * 메시지는 재시도된다(at-least-once) — 핸들러가 멱등(event_id UNIQUE)이라 중복은 안전하다.
 * 재시도/드롭 정책은 {@code spring.rabbitmq.listener.simple.retry}로 설정(무한 재큐 방지).
 */
@Component
@ConditionalOnProperty(name = "outbox.publisher", havingValue = "rabbit")
@RequiredArgsConstructor
public class OutboxEventConsumer {

    private final EventDispatcher dispatcher;

    @RabbitListener(queues = RabbitConfig.NOTIFICATIONS_QUEUE)
    @Transactional
    public void onMessage(OutboxMessage message) {
        OutboxEvent event = OutboxEvent.received(
                message.eventId(), message.eventType(),
                message.aggregateType(), message.aggregateId(), message.payload());
        dispatcher.dispatch(event);
    }
}
