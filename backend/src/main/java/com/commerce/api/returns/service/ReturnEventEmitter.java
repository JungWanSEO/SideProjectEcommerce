package com.commerce.api.returns.service;

import com.commerce.api.global.outbox.OutboxService;
import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.event.ReturnStatusChangedPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 반품/교환 상태 변경 → 구매자 알림 이벤트 발행(#6 P2). {@link OutboxService#append}가 호출자 트랜잭션에
 * 합류하므로 상태 전이와 이벤트 INSERT가 한 커밋이 된다(원자성).
 *
 * <p>발행 지점 둘: ① 요청 생성(REQUESTED, {@code create}) — 구매자 본인 행위라 <b>구매자</b>에겐 알림 안 하지만
 * <b>셀러</b>에겐 "요청 접수"를 알린다(P3b). ② 셀러/ADMIN 처리 전이(승인·거부·수거·검수·환불·교환완료,
 * {@link ReturnService#applyAction} 직후) — <b>구매자</b>에게 진행을 알린다. 각 핸들러가 관심 상태·수신자만 골라 처리한다.
 */
@Component
@RequiredArgsConstructor
public class ReturnEventEmitter {

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public void emitStatusChanged(ReturnRequest r) {
        outboxService.append(
                "RETURN_STATUS_CHANGED",
                "RETURN",
                String.valueOf(r.getId()),
                toJson(new ReturnStatusChangedPayload(
                        r.getId(), r.getOrderId(), r.getMemberId(), r.getSellerId(),
                        r.getStatus().name(), r.getType().name())));
    }

    private String toJson(ReturnStatusChangedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 직렬화 실패", e);
        }
    }
}
