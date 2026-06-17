package com.commerce.api.order.dto;

import com.commerce.api.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 주문 배송 상태 전진 요청(ADMIN). 다음 상태(SHIPPING 또는 DELIVERED)를 보낸다.
 * 실제 전이 가드(forward-only)는 {@code Order.advanceShipping}가 강제한다 — 잘못된 전이면 409.
 */
public record OrderStatusUpdateRequest(
        @NotNull(message = "변경할 상태는 필수입니다.")
        @Schema(description = "다음 배송 상태 (SHIPPING 또는 DELIVERED)", example = "SHIPPING")
        OrderStatus status
) {
}
