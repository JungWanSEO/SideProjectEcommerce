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
 * SellerReturnRequestedHandler 단위 테스트 — REQUESTED만 셀러에게, 그 외 전이·셀러 미상은 생략 + 멱등.
 */
@ExtendWith(MockitoExtension.class)
class SellerReturnRequestedHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SellerReturnRequestedHandler handler;

    private static final long ORDER_ID = 10L;
    private static final long BUYER_ID = 99L;
    private static final long SELLER_ID = 700L;

    @BeforeEach
    void setUp() {
        handler = new SellerReturnRequestedHandler(notificationRepository, objectMapper);
    }

    private OutboxEvent event(long id, String status, String type) throws Exception {
        String payload = objectMapper.writeValueAsString(
                new ReturnStatusChangedPayload(7L, ORDER_ID, BUYER_ID, SELLER_ID, status, type));
        OutboxEvent e = OutboxEvent.pending("RETURN_STATUS_CHANGED", "RETURN", "7", payload);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    @DisplayName("REQUESTED(반품) → 셀러 인박스에 '반품 요청이 접수되었습니다' + 딥링크")
    void handle_returnRequested() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.SELLER, SELLER_ID)).willReturn(false);

        handler.handle(event(1L, "REQUESTED", "RETURN"));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository).save(captor.capture());
        NotificationLog saved = captor.getValue();
        assertThat(saved.getRecipientType()).isEqualTo(RecipientType.SELLER);
        assertThat(saved.getRecipientId()).isEqualTo(SELLER_ID);
        assertThat(saved.getMessage()).contains("반품 요청이 접수").contains("10");
        assertThat(saved.getLink()).isEqualTo("/seller/returns");
    }

    @Test
    @DisplayName("REQUESTED(교환) → '교환 요청이 접수되었습니다'")
    void handle_exchangeRequested() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.SELLER, SELLER_ID)).willReturn(false);

        handler.handle(event(1L, "REQUESTED", "EXCHANGE"));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getMessage()).contains("교환 요청이 접수");
    }

    @Test
    @DisplayName("REQUESTED 외 전이(APPROVED)는 셀러 알림 생성 안 함(구매자 핸들러 몫)")
    void handle_nonRequestedSkipped() throws Exception {
        handler.handle(event(1L, "APPROVED", "RETURN"));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("멱등 - 같은 이벤트·셀러면 스킵")
    void handle_idempotentSkip() throws Exception {
        given(notificationRepository.existsByEventIdAndRecipientTypeAndRecipientId(
                1L, RecipientType.SELLER, SELLER_ID)).willReturn(true);

        handler.handle(event(1L, "REQUESTED", "RETURN"));

        verify(notificationRepository, never()).save(any());
    }
}
