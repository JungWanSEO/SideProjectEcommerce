package com.commerce.api.notification.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.outbox.OutboxEvent;
import com.commerce.api.notification.entity.NotificationCategory;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import com.commerce.api.order.event.OrderStatusChangedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OrderStatusChangedHandler 단위 테스트 — 주문 상태 변화를 구매자 인박스에 상태별 문구로 기록 + 멱등/필터.
 */
@ExtendWith(MockitoExtension.class)
class OrderStatusChangedHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderStatusChangedHandler handler;

    private static final long ORDER_ID = 10L;
    private static final long BUYER_ID = 99L;

    @BeforeEach
    void setUp() {
        handler = new OrderStatusChangedHandler(notificationRepository, objectMapper);
    }

    private OutboxEvent event(long id, String status) throws Exception {
        String payload = objectMapper.writeValueAsString(
                new OrderStatusChangedPayload(ORDER_ID, BUYER_ID, status));
        OutboxEvent e = OutboxEvent.pending("ORDER_STATUS_CHANGED", "ORDER", "10", payload);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    @DisplayName("SHIPPING → 구매자 인박스에 '배송이 시작되었습니다' + 딥링크")
    void handle_shipping() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.BUYER, BUYER_ID)).willReturn(false);

        handler.handle(event(1L, "SHIPPING"));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository).save(captor.capture());
        NotificationLog saved = captor.getValue();
        assertThat(saved.getRecipientType()).isEqualTo(RecipientType.BUYER);
        assertThat(saved.getRecipientId()).isEqualTo(BUYER_ID);
        assertThat(saved.getCategory()).isEqualTo(NotificationCategory.TRANSACTIONAL);
        assertThat(saved.getMessage()).contains("배송이 시작").contains("10");
        assertThat(saved.getLink()).isEqualTo("/orders/10");
    }

    @Test
    @DisplayName("관심 밖 상태(PAID)는 알림 생성 안 함")
    void handle_uninterestingStatusSkipped() throws Exception {
        handler.handle(event(1L, "PAID"));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("멱등 - 같은 이벤트·수신자면 스킵")
    void handle_idempotentSkip() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.BUYER, BUYER_ID)).willReturn(true);

        handler.handle(event(1L, "DELIVERED"));

        verify(notificationRepository, never()).save(any());
    }
}
