package com.commerce.api.global.outbox;

/**
 * RabbitMQ로 오가는 아웃박스 이벤트 메시지(전송 DTO·JSON 직렬화 대상).
 *
 * <p>JPA 엔티티 {@link OutboxEvent}를 그대로 보내지 않고, 소비자가 핸들러 처리에 필요한 값만 담는다.
 * {@code eventId}는 발행자의 outbox 행 id로, 소비자의 <b>멱등 키</b>가 된다(NotificationLog.event_id UNIQUE).
 */
public record OutboxMessage(
        Long eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payload
) {
    /** 발행 시 엔티티에서 메시지로 변환. */
    public static OutboxMessage from(OutboxEvent event) {
        return new OutboxMessage(event.getId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId(), event.getPayload());
    }
}
