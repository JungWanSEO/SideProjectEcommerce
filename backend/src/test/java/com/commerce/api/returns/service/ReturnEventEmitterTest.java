package com.commerce.api.returns.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.outbox.OutboxService;
import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ReturnEventEmitter 단위 테스트 — 전이 후 RETURN_STATUS_CHANGED 발행(수신자·상태·타입 포함).
 */
@ExtendWith(MockitoExtension.class)
class ReturnEventEmitterTest {

    @Mock
    private OutboxService outboxService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReturnEventEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new ReturnEventEmitter(outboxService, objectMapper);
    }

    @Test
    @DisplayName("전이 후 발행 - RETURN_STATUS_CHANGED에 returnId·수신자·상태·타입이 담긴다")
    void emit_appendsEvent() {
        ReturnRequest r = mock(ReturnRequest.class);
        given(r.getId()).willReturn(7L);
        given(r.getOrderId()).willReturn(10L);
        given(r.getMemberId()).willReturn(99L);
        given(r.getStatus()).willReturn(ReturnStatus.APPROVED);
        given(r.getType()).willReturn(ReturnType.RETURN);

        emitter.emitStatusChanged(r);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxService).append(eq("RETURN_STATUS_CHANGED"), eq("RETURN"), eq("7"), payload.capture());
        assertThat(payload.getValue()).contains("APPROVED").contains("99").contains("RETURN");
    }
}
