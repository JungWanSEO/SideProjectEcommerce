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
        String memo
) {
}
