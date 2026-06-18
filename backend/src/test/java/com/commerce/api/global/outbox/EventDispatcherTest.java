package com.commerce.api.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EventDispatcher 단위 테스트 — eventType이 맞는 핸들러만 호출, 구독자 없으면 no-op.
 */
class EventDispatcherTest {

    /** 호출된 이벤트를 기록하는 가짜 핸들러. */
    private static class RecordingHandler implements OutboxEventHandler {
        private final String type;
        final List<OutboxEvent> handled = new ArrayList<>();

        RecordingHandler(String type) {
            this.type = type;
        }

        @Override
        public String eventType() {
            return type;
        }

        @Override
        public void handle(OutboxEvent event) {
            handled.add(event);
        }
    }

    @Test
    @DisplayName("디스패치 - 타입이 맞는 핸들러만 호출(다른 타입 핸들러는 호출 안 됨)")
    void dispatch_callsMatchingHandlerOnly() {
        RecordingHandler paid = new RecordingHandler("PAYMENT_COMPLETED");
        RecordingHandler other = new RecordingHandler("ORDER_SHIPPED");
        EventDispatcher dispatcher = new EventDispatcher(List.of(paid, other));

        dispatcher.dispatch(OutboxEvent.received(1L, "PAYMENT_COMPLETED", "PAYMENT", "5", "{}"));

        assertThat(paid.handled).hasSize(1);
        assertThat(other.handled).isEmpty();
    }

    @Test
    @DisplayName("디스패치 - 구독 핸들러가 없는 타입이면 no-op(예외 없음)")
    void dispatch_noHandler_isNoOp() {
        EventDispatcher dispatcher = new EventDispatcher(List.of());

        dispatcher.dispatch(OutboxEvent.received(1L, "UNKNOWN", null, null, "{}"));
        // 예외 없이 통과하면 성공
    }
}
