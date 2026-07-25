package com.commerce.api.order.dto;

import com.commerce.api.order.entity.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 배송(shipment) 상태 전진 요청(#1 c안) — 셀러/ADMIN이 자기(또는 지정) shipment를 다음 단계로 보낸다.
 * 전이 가드(forward-only PAID→SHIPPING→DELIVERED)는 {@code Shipment.advanceShipping}가 강제한다(잘못된 전이면 409).
 *
 * <p>SHIPPING으로 보낼 때 택배사·운송장을 함께 실으면 그 shipment에 저장돼 구매자에게 노출된다(선택). DELIVERED엔 무시.
 */
public record ShipmentStatusUpdateRequest(
        @NotNull(message = "변경할 상태는 필수입니다.")
        @Schema(description = "다음 배송 상태 (SHIPPING 또는 DELIVERED)", example = "SHIPPING")
        ShipmentStatus status,

        @Size(max = 40, message = "택배사는 40자 이내여야 합니다.")
        @Schema(description = "택배사(SHIPPING일 때·선택)", example = "CJ대한통운")
        String courier,

        @Size(max = 60, message = "운송장 번호는 60자 이내여야 합니다.")
        @Schema(description = "운송장 번호(SHIPPING일 때·선택)", example = "1234567890123")
        String trackingNumber
) {
}
