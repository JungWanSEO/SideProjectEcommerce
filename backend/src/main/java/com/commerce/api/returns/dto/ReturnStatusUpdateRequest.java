package com.commerce.api.returns.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반품 처리 요청(셀러/ADMIN, #3) — 다음 액션(승인·거부·수거·검수 …)을 보낸다.
 * 전이 가드는 {@code ReturnRequest}가 강제한다(잘못된 전이·타입 불일치 409).
 */
public record ReturnStatusUpdateRequest(
        @NotNull(message = "처리 액션은 필수입니다.")
        @Schema(description = "APPROVE / REJECT / PICK_UP / INSPECT / REFUND / COMPLETE", example = "APPROVE")
        ReturnAction action,

        @Size(max = 255, message = "메모는 255자 이내여야 합니다.")
        @Schema(description = "거부 사유 등 메모(선택)", example = "사용 흔적으로 검수 불합격")
        String memo,

        @Schema(description = """
                귀책 주체(선택, #8 후속). INSPECT와 함께 보내면 그 값으로 확정하고, 생략하면 구매자가 신고한 \
                사유에서 파생한다. ADMIN은 종료 전(REFUND/COMPLETE 이전) 어떤 액션에든 실어 보내 재정할 수 있다. \
                셀러는 검수(INSPECT) 시점에만 정할 수 있다.""",
                example = "CUSTOMER")
        com.commerce.api.global.common.CancelReason.Fault faultParty
) {
    /**
     * 귀책 미지정 편의 생성자 — 기존 호출부(테스트·데모 시더) 무변경.
     * 귀책이 null이면 구매자 신고 사유에서 파생되므로 기존 동작과 동일하다.
     * (SettlementSearchCondition·AuditLogSearchCondition과 같은 canonical + 편의 생성자 패턴.)
     */
    public ReturnStatusUpdateRequest(ReturnAction action, String memo) {
        this(action, memo, null);
    }
}
