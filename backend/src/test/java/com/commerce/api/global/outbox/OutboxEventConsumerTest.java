package com.commerce.api.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OutboxEventConsumer 단위 테스트 — 받은 메시지를 비영속 이벤트로 복원해 디스패처로 넘기는지 검증(브로커 불필요).
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventConsumerTest {

    @Mock
    private EventDispatcher dispatcher;
    @InjectMocks
    private OutboxEventConsumer consumer;

    @Test
    @DisplayName("소비 - 메시지를 OutboxEvent로 복원(eventId/타입/payload 보존)해 디스패치")
    void onMessage_dispatches() {
        OutboxMessage message =
                new OutboxMessage(7L, "PAYMENT_COMPLETED", "PAYMENT", "5", "{\"orderId\":1}");

        consumer.onMessage(message);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(dispatcher).dispatch(captor.capture());
        OutboxEvent dispatched = captor.getValue();
        assertThat(dispatched.getId()).isEqualTo(7L);   // 멱등 키 보존
        assertThat(dispatched.getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(dispatched.getPayload()).isEqualTo("{\"orderId\":1}");
    }
}
