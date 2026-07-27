package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.outbox.OutboxService;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OrderEventEmitter 단위 테스트 — "관심 상태로 새로 바뀌었을 때만" 발행(불변·관심 밖이면 no-op).
 */
@ExtendWith(MockitoExtension.class)
class OrderEventEmitterTest {

    @Mock
    private OutboxService outboxService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderEventEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new OrderEventEmitter(outboxService, objectMapper);
    }

    @Test
    @DisplayName("PAID→SHIPPING 새 전이 → ORDER_STATUS_CHANGED 발행(수신자·상태 포함)")
    void emits_onNewNotifiableStatus() {
        Order order = mock(Order.class);
        given(order.getStatus()).willReturn(OrderStatus.SHIPPING);
        given(order.getId()).willReturn(10L);
        given(order.getMemberId()).willReturn(99L);

        emitter.emitIfStatusBecameNotifiable(order, OrderStatus.PAID);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxService).append(eq("ORDER_STATUS_CHANGED"), eq("ORDER"), eq("10"), payload.capture());
        assertThat(payload.getValue()).contains("SHIPPING").contains("99");
    }

    @Test
    @DisplayName("상태 불변(SHIPPING→SHIPPING) → 발행 안 함(알림 스팸 방지)")
    void noEmit_whenUnchanged() {
        Order order = mock(Order.class);
        given(order.getStatus()).willReturn(OrderStatus.SHIPPING);

        emitter.emitIfStatusBecameNotifiable(order, OrderStatus.SHIPPING);

        verify(outboxService, never()).append(any(), any(), any(), any());
    }

    @Test
    @DisplayName("관심 밖 전이(PENDING→PAID) → 발행 안 함")
    void noEmit_whenNotNotifiable() {
        Order order = mock(Order.class);
        given(order.getStatus()).willReturn(OrderStatus.PAID);

        emitter.emitIfStatusBecameNotifiable(order, OrderStatus.PENDING);

        verify(outboxService, never()).append(any(), any(), any(), any());
    }

    @Test
    @DisplayName("상태-확정 발행(전체 취소) → CANCELLED 이벤트 발행(엔티티 없이 orderId·buyerId로)")
    void emitOrderStatusChanged_cancelled() {
        emitter.emitOrderStatusChanged(10L, 99L, OrderStatus.CANCELLED);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxService).append(eq("ORDER_STATUS_CHANGED"), eq("ORDER"), eq("10"), payload.capture());
        assertThat(payload.getValue()).contains("CANCELLED").contains("99");
    }

    @Test
    @DisplayName("상태-확정 발행 - 관심 밖 상태(PAID)는 발행 안 함(방어)")
    void emitOrderStatusChanged_notNotifiable() {
        emitter.emitOrderStatusChanged(10L, 99L, OrderStatus.PAID);

        verify(outboxService, never()).append(any(), any(), any(), any());
    }
}
