package com.commerce.api.notification.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.outbox.OutboxEvent;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import com.commerce.api.returns.event.ReturnStatusChangedPayload;
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
 * ReturnStatusChangedHandler 단위 테스트 — 반품/교환 전이를 구매자 인박스에 상태별 문구로 기록 + 멱등/필터.
 */
@ExtendWith(MockitoExtension.class)
class ReturnStatusChangedHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReturnStatusChangedHandler handler;

    private static final long RETURN_ID = 7L;
    private static final long ORDER_ID = 10L;
    private static final long BUYER_ID = 99L;

    @BeforeEach
    void setUp() {
        handler = new ReturnStatusChangedHandler(notificationRepository, objectMapper);
    }

    private OutboxEvent event(long id, String status, String type) throws Exception {
        String payload = objectMapper.writeValueAsString(
                new ReturnStatusChangedPayload(RETURN_ID, ORDER_ID, BUYER_ID, status, type));
        OutboxEvent e = OutboxEvent.pending("RETURN_STATUS_CHANGED", "RETURN", "7", payload);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    @DisplayName("APPROVED → 구매자 인박스에 '승인' 문구 + 딥링크")
    void handle_approved() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.BUYER, BUYER_ID)).willReturn(false);

        handler.handle(event(1L, "APPROVED", "RETURN"));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository).save(captor.capture());
        NotificationLog saved = captor.getValue();
        assertThat(saved.getRecipientType()).isEqualTo(RecipientType.BUYER);
        assertThat(saved.getRecipientId()).isEqualTo(BUYER_ID);
        assertThat(saved.getMessage()).contains("승인").contains("10");
        assertThat(saved.getLink()).isEqualTo("/orders/10");
    }

    @Test
    @DisplayName("REFUNDED → '반품 환불이 완료되었습니다'")
    void handle_refunded() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.BUYER, BUYER_ID)).willReturn(false);

        handler.handle(event(1L, "REFUNDED", "RETURN"));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getMessage()).contains("환불");
    }

    @Test
    @DisplayName("매핑에 없는 상태(REQUESTED)는 생략")
    void handle_unmappedStatusSkipped() throws Exception {
        handler.handle(event(1L, "REQUESTED", "RETURN"));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("멱등 - 같은 이벤트·수신자면 스킵")
    void handle_idempotentSkip() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.BUYER, BUYER_ID)).willReturn(true);

        handler.handle(event(1L, "COMPLETED", "EXCHANGE"));

        verify(notificationRepository, never()).save(any());
    }
}
