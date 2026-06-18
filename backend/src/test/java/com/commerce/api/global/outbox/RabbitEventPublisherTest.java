package com.commerce.api.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RabbitEventPublisher 단위 테스트 — 브로커 없이 RabbitTemplate을 목으로 발행 호출만 검증.
 */
@ExtendWith(MockitoExtension.class)
class RabbitEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @InjectMocks
    private RabbitEventPublisher publisher;

    @Test
    @DisplayName("발행 - 익스체인지로 라우팅키=eventType, 본문=OutboxMessage(엔티티 값 복사) 전송")
    void publish_sendsToExchange() {
        OutboxEvent event = OutboxEvent.pending("PAYMENT_COMPLETED", "PAYMENT", "5", "{\"orderId\":1}");
        ReflectionTestUtils.setField(event, "id", 7L);   // IDENTITY id는 영속 전 null → 주입

        publisher.publish(event);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EVENTS_EXCHANGE), eq("PAYMENT_COMPLETED"), captor.capture());
        OutboxMessage sent = captor.getValue();
        assertThat(sent.eventId()).isEqualTo(7L);              // 멱등 키 = 발행자 outbox 행 id
        assertThat(sent.eventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(sent.payload()).isEqualTo("{\"orderId\":1}");
    }
}
