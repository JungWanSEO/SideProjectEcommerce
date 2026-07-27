package com.commerce.api.notification.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.outbox.OutboxEvent;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
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
 * SellerNewOrderHandler 단위 테스트 — PAYMENT_COMPLETED fan-out(1 이벤트 → N 셀러) + 플랫폼 제외 + 멱등.
 */
@ExtendWith(MockitoExtension.class)
class SellerNewOrderHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private OrderRepository orderRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SellerNewOrderHandler handler;

    private static final long ORDER_ID = 10L;

    @BeforeEach
    void setUp() {
        handler = new SellerNewOrderHandler(notificationRepository, orderRepository, objectMapper);
    }

    private OrderItem item(Long sellerId) {
        return OrderItem.builder()
                .productId(1L).optionId(1L).sellerId(sellerId)
                .productName("P").size("M").orderPrice(1000L).quantity(1).build();
    }

    private OutboxEvent event(long id) throws Exception {
        String payload = objectMapper.writeValueAsString(new PaymentCompletedPayload(ORDER_ID, 5L, 3000L));
        OutboxEvent e = OutboxEvent.pending("PAYMENT_COMPLETED", "PAYMENT", "5", payload);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    @DisplayName("fan-out - 셀러 1·2·플랫폼(null) 주문 → 셀러 1·2에게 각각 알림, 플랫폼 제외(2건)")
    void handle_fansOutToDistinctSellers() throws Exception {
        Order order = Order.create(100L);
        order.addItem(item(1L));
        order.addItem(item(2L));
        order.addItem(item(null));   // 플랫폼 직매입 — 알림 대상 아님
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.SELLER, 1L)).willReturn(false);
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.SELLER, 2L)).willReturn(false);

        handler.handle(event(1L));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(n -> n.getRecipientType() == RecipientType.SELLER);
        assertThat(captor.getAllValues()).extracting(NotificationLog::getRecipientId)
                .containsExactlyInAnyOrder(1L, 2L);   // 플랫폼(null)은 없음
        assertThat(captor.getAllValues()).allMatch(n -> n.getMessage().contains("새 주문"));
    }

    @Test
    @DisplayName("멱등 - 한 셀러는 이미 알림 있음 → 그 셀러만 스킵, 나머지 셀러는 생성")
    void handle_idempotentPerSeller() throws Exception {
        Order order = Order.create(100L);
        order.addItem(item(1L));
        order.addItem(item(2L));
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.SELLER, 1L)).willReturn(true);    // 셀러1은 이미 처리됨
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.SELLER, 2L)).willReturn(false);

        handler.handle(event(1L));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo(2L);
    }
}
