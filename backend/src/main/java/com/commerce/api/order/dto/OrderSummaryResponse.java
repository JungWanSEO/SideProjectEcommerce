package com.commerce.api.order.dto;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import java.time.LocalDateTime;

/**
 * 주문 목록용 요약 응답.
 *
 * <p>목록에서는 전체 항목 대신 <b>요약만</b> 내린다(전체 항목은 상세 {@code GET /api/orders/{id}}에서).
 * 대표상품명 + 항목 수를 주면 클라이언트가 "○○ 외 N건" 형태로 렌더할 수 있다.
 * (목록=요약, 상세=전체 — 패션 셀렉트샵식 주문 목록 UX)
 */
public record OrderSummaryResponse(
        Long id,
        Long memberId,                     // 주문 회원 ID — 어드민 주문 목록에서 누구 주문인지 식별용
        OrderStatus status,
        long totalPrice,
        LocalDateTime createdAt,
        String representativeProductName,  // 첫 항목의 상품명(대표). 항목이 없으면 null
        int itemCount                      // 주문 항목(라인) 수
) {
    public static OrderSummaryResponse from(Order order) {
        var items = order.getOrderItems();
        String representative = items.isEmpty() ? null : items.get(0).getProductName();
        return new OrderSummaryResponse(
                order.getId(),
                order.getMemberId(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getCreatedAt(),
                representative,
                items.size()
        );
    }
}
