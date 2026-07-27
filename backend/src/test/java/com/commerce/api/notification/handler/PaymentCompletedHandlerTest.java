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
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.event.PaymentCompletedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PaymentCompletedHandler 단위 테스트 — 구매자 인박스에 알림 기록 + 멱등 소비(중복 디스패치 스킵).
 */
@ExtendWith(MockitoExtension.class)
class PaymentCompletedHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private OrderRepository orderRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentCompletedHandler handler;

    private static final long ORDER_ID = 10L;
    private static final long BUYER_ID = 99L;

    @BeforeEach
    void setUp() {
        handler = new PaymentCompletedHandler(notificationRepository, orderRepository, objectMapper);
    }

    private OutboxEvent event(long id) throws Exception {
        String payload = objectMapper.writeValueAsString(new PaymentCompletedPayload(ORDER_ID, 5L, 30000L));
        OutboxEvent e = OutboxEvent.pending("PAYMENT_COMPLETED", "PAYMENT", "5", payload);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    @DisplayName("결제완료 이벤트 → 구매자 인박스에 알림(주문·금액·딥링크·거래성)")
    void handle_createsBuyerNotification() throws Exception {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(Order.create(BUYER_ID)));
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.BUYER, BUYER_ID)).willReturn(false);

        handler.handle(event(1L));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository).save(captor.capture());
        NotificationLog saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(1L);
        assertThat(saved.getRecipientType()).isEqualTo(RecipientType.BUYER);
        assertThat(saved.getRecipientId()).isEqualTo(BUYER_ID);
        assertThat(saved.getType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(saved.getCategory()).isEqualTo(NotificationCategory.TRANSACTIONAL);
        assertThat(saved.getMessage()).contains("10").contains("30000");
        assertThat(saved.getLink()).isEqualTo("/orders/10");
    }

    @Test
    @DisplayName("멱등 - 같은 이벤트·수신자로 이미 처리했으면 스킵(중복 발송 안 함)")
    void handle_idempotentSkip() throws Exception {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(Order.create(BUYER_ID)));
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.BUYER, BUYER_ID)).willReturn(true);

        handler.handle(event(1L));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("주문을 못 찾으면 수신자 미상 → 알림 생략")
    void handle_orderNotFound_skips() throws Exception {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

        handler.handle(event(1L));

        verify(notificationRepository, never()).save(any());
    }
}
