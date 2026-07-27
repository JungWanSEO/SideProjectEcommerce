package com.commerce.api.returns.dto;

import com.commerce.api.global.common.CancelReason;
import com.commerce.api.returns.entity.ReturnType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반품/교환 요청 생성(구매자, #3). sellerId·shipmentId·quantity는 서버가 대상 주문 항목에서 도출한다(클라 입력 금지 — IDOR).
 * 교환(EXCHANGE)이면 exchangeOptionId 필수(같은 상품 다른 옵션), 반품(RETURN)이면 null.
 */
public record ReturnCreateRequest(
        @NotNull(message = "주문 항목 ID는 필수입니다.")
        @Schema(description = "반품/교환할 주문 항목 ID", example = "10")
        Long orderItemId,

        @NotNull(message = "반품 종류는 필수입니다.")
        @Schema(description = "RETURN(환불) 또는 EXCHANGE(교환)", example = "RETURN")
        ReturnType type,

        @Size(max = 255, message = "사유는 255자 이내여야 합니다.")
        @Schema(description = "자유텍스트 상세 사유", example = "단순 변심")
        String reason,

        @Schema(description = "구조화된 사유 코드(#8, 기록·집계용·선택)", example = "CHANGE_OF_MIND")
        CancelReason reasonCode,

        @Schema(description = "교환 대상 옵션 ID(EXCHANGE일 때 필수, 같은 상품 다른 옵션)", example = "22")
        Long exchangeOptionId
) {
}
