package com.commerce.api.returns.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ReturnRequest 상태머신 단위 테스트(#3 P2) — forward-only 전이·타입 제약·이력 불변식.
 * 실제 상태/원장 이동(환불·재고·재출고)은 후속 phase(P4/P6). 여기선 워크플로 전이만 검증.
 */
class ReturnRequestTest {

    private ReturnRequest returnReq() {
        return ReturnRequest.create(1L, 10L, 100L, 7L, 500L, ReturnType.RETURN, "단순변심", null, 1, null);
    }

    private ReturnRequest exchangeReq() {
        return ReturnRequest.create(1L, 10L, 100L, 7L, 500L, ReturnType.EXCHANGE, "사이즈 교환", null, 1, 22L);
    }

    @Test
    @DisplayName("생성 - REQUESTED + 이력 1건(null→REQUESTED), 기본 재입고 true")
    void create() {
        ReturnRequest r = returnReq();
        assertThat(r.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(r.isRestock()).isTrue();
        assertThat(r.getStatusHistory()).hasSize(1);
        assertThat(r.getStatusHistory().get(0).getToStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(r.belongsToSeller(7L)).isTrue();
        assertThat(r.belongsToSeller(8L)).isFalse();
    }

    @Test
    @DisplayName("사유 코드(#8) - 생성 시 구조화된 reasonCode를 저장한다(기록·집계 전용)")
    void create_storesReasonCode() {
        ReturnRequest r = ReturnRequest.create(1L, 10L, 100L, 7L, 500L, ReturnType.RETURN, "불량이에요",
                com.commerce.api.global.common.CancelReason.DEFECTIVE, 1, null);
        assertThat(r.getReasonCode()).isEqualTo(com.commerce.api.global.common.CancelReason.DEFECTIVE);
        assertThat(returnReq().getReasonCode()).isNull();   // 미지정이면 null(레거시 허용)
    }

    @Test
    @DisplayName("생성 검증 - 교환은 옵션 필수, 반품은 옵션 금지(400)")
    void createValidation() {
        assertThatThrownBy(() -> ReturnRequest.create(1L, 10L, 100L, 7L, 500L, ReturnType.EXCHANGE, "x", null, 1, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReturnRequest.create(1L, 10L, 100L, 7L, 500L, ReturnType.RETURN, "x", null, 1, 22L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("RETURN 정상 경로 - 요청→승인→수거→검수→환불확정")
    void returnHappyPath() {
        ReturnRequest r = returnReq();
        r.approve(7L);
        r.pickUp(7L);
        r.inspect(7L);
        r.markRefunded(4500L, true, 7L);

        assertThat(r.getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(r.getRefundAmount()).isEqualTo(4500L);
        assertThat(r.isTerminal()).isTrue();
        assertThat(r.getStatusHistory()).hasSize(5);
    }

    @Test
    @DisplayName("EXCHANGE 정상 경로 - 요청→승인→수거→검수→교환완료(재출고 shipment 연결)")
    void exchangeHappyPath() {
        ReturnRequest r = exchangeReq();
        r.approve(7L);
        r.pickUp(7L);
        r.inspect(7L);
        r.markExchanged(999L, 7L);

        assertThat(r.getStatus()).isEqualTo(ReturnStatus.COMPLETED);
        assertThat(r.getExchangeShipmentId()).isEqualTo(999L);
        assertThat(r.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("거부 - 요청 거부·검수 불합격 둘 다 REJECTED(금액·재고 무영향)")
    void reject() {
        ReturnRequest fromRequested = returnReq();
        fromRequested.reject(7L, "기간 초과");
        assertThat(fromRequested.getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(fromRequested.getRefundAmount()).isNull();

        ReturnRequest fromInspected = returnReq();
        fromInspected.approve(7L);
        fromInspected.pickUp(7L);
        fromInspected.inspect(7L);
        fromInspected.reject(7L, "사용 흔적 — 검수 불합격");
        assertThat(fromInspected.getStatus()).isEqualTo(ReturnStatus.REJECTED);
    }

    @Test
    @DisplayName("전이 위반 - 잘못된 순서·타입 불일치는 409")
    void illegalTransitions() {
        assertThatThrownBy(() -> returnReq().pickUp(7L))            // REQUESTED→PICKED_UP 건너뛰기
                .isInstanceOf(BusinessException.class);

        ReturnRequest inspected = returnReq();
        inspected.approve(7L); inspected.pickUp(7L); inspected.inspect(7L);
        assertThatThrownBy(() -> inspected.markExchanged(9L, 7L))   // RETURN에 교환확정
                .isInstanceOf(BusinessException.class);

        ReturnRequest exInspected = exchangeReq();
        exInspected.approve(7L); exInspected.pickUp(7L); exInspected.inspect(7L);
        assertThatThrownBy(() -> exInspected.markRefunded(1000L, true, 7L))   // EXCHANGE에 환불확정
                .isInstanceOf(BusinessException.class);
    }
}
