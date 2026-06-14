package com.commerce.api.order.dto;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.ShippingInfo;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 응답.
 */
public record OrderResponse(
        Long id,
        Long memberId,
        OrderStatus status,
        long totalPrice,
        List<OrderItemResponse> items,
        ShippingResponse shipping,   // 배송지 스냅샷 (없으면 null)
        LocalDateTime createdAt
) {
    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getOrderItems().stream()
                .map(OrderItemResponse::from)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getMemberId(),
                order.getStatus(),
                order.getTotalPrice(),
                items,
                ShippingResponse.from(order.getShippingInfo()),
                order.getCreatedAt()
        );
    }

    /** 배송지 응답 (주문 시점 스냅샷). 배송지가 없는 주문이면 null. */
    public record ShippingResponse(
            String recipient,
            String phone,
            String zipcode,
            String address1,
            String address2,
            String deliveryMemo
    ) {
        static ShippingResponse from(ShippingInfo s) {
            // 임베디드가 비었거나(명시적 주문) 핵심 필드가 없으면 배송지 없음으로 본다.
            if (s == null || s.getRecipient() == null) {
                return null;
            }
            return new ShippingResponse(
                    s.getRecipient(), s.getPhone(), s.getZipcode(),
                    s.getAddress1(), s.getAddress2(), s.getDeliveryMemo());
        }
    }

    /** 주문 항목 응답 (스냅샷된 상품명·사이즈·가격·셀러귀속 + 소계) */
    public record OrderItemResponse(
            Long productId,
            Long optionId,
            Long brandId,     // 주문 시점 스냅샷 (미지정이면 null)
            Long sellerId,    // 주문 시점 스냅샷 (셀러별 정산 귀속, 미귀속이면 null)
            String productName,
            String size,
            long orderPrice,
            int quantity,
            long subtotal
    ) {
        public static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(
                    item.getProductId(),
                    item.getOptionId(),
                    item.getBrandId(),
                    item.getSellerId(),
                    item.getProductName(),
                    item.getSize(),
                    item.getOrderPrice(),
                    item.getQuantity(),
                    item.getSubtotal()
            );
        }
    }
}