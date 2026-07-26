package com.commerce.api.order.dto;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderItemStatus;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.entity.ShippingInfo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 주문 응답.
 */
public record OrderResponse(
        Long id,
        Long memberId,
        OrderStatus status,
        long totalPrice,          // 할인 전 총액(gross)
        long discountAmount,      // 쿠폰 할인액 (없으면 0)
        long shippingFee,         // 배송비(#4, 없으면 0). 플랫폼 수익 — 셀러 정산 net에는 미포함
        long payableAmount,       // 실제 결제액 = totalPrice - discountAmount + shippingFee (활성 항목 있을 때)
        String couponCode,        // 적용된 쿠폰 코드 (없으면 null)
        List<OrderItemResponse> items,
        ShippingResponse shipping,   // 배송지 스냅샷 (없으면 null)
        List<StatusHistoryResponse> statusHistory,   // 주문 상태 타임라인 (발생 순, shipment rollup 파생)
        List<ShipmentResponse> shipments,            // 셀러별 배송 단위(#1 c안) — 상태·송장(택배사/운송장은 여기에). 결제 전(PENDING)은 빈 목록
        LocalDateTime createdAt
) {
    public static OrderResponse from(Order order) {
        // 쿠폰 할인을 항목별로 안분(매출 비례)한 값을 항목 응답에 실어, 환불액·정산이 같은 출처를 쓰게 한다.
        Map<OrderItem, Long> shares = order.discountShares();
        List<OrderItemResponse> items = order.getOrderItems().stream()
                .map(item -> OrderItemResponse.from(item, shares.getOrDefault(item, 0L)))
                .toList();
        List<StatusHistoryResponse> history = order.getStatusHistory().stream()
                .map(StatusHistoryResponse::from)
                .toList();
        List<ShipmentResponse> shipments = order.getShipments().stream()
                .map(ShipmentResponse::from)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getMemberId(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getDiscountAmount(),
                order.getShippingFee(),
                order.getPayableAmount(),
                order.getCouponCode(),
                items,
                ShippingResponse.from(order.getShippingInfo()),
                history,
                shipments,
                order.getCreatedAt()
        );
    }

    /** 셀러별 배송 단위 응답(#1 c안) — 셀러가 자기 shipment 상태·송장을 보고, 구매자는 셀러별 배송 진행을 본다. */
    public record ShipmentResponse(
            Long id,
            Long sellerId,            // null = 플랫폼 직매입 버킷
            ShipmentStatus status,    // PAID / SHIPPING / DELIVERED / CANCELLED
            String courier,
            String trackingNumber
    ) {
        static ShipmentResponse from(Shipment s) {
            return new ShipmentResponse(
                    s.getId(), s.getSellerId(), s.getStatus(), s.getCourier(), s.getTrackingNumber());
        }
    }

    /** 상태 이력 1건 — 주문 상세 타임라인용. */
    public record StatusHistoryResponse(
            OrderStatus fromStatus,   // 이전 상태 (생성 시 null)
            OrderStatus toStatus,
            Long changedBy,           // 변경 주체 회원 ID (시스템/스케줄러면 null)
            String memo,
            LocalDateTime createdAt
    ) {
        static StatusHistoryResponse from(com.commerce.api.order.entity.OrderStatusHistory h) {
            return new StatusHistoryResponse(
                    h.getFromStatus(), h.getToStatus(), h.getChangedBy(), h.getMemo(), h.getCreatedAt());
        }
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

    /** 주문 항목 응답 (스냅샷된 상품명·사이즈·가격·셀러귀속 + 소계 + 상태) */
    public record OrderItemResponse(
            Long id,          // 주문 항목 ID (부분취소 대상 지정·정산 상계 식별용)
            Long productId,
            Long optionId,
            Long brandId,     // 주문 시점 스냅샷 (미지정이면 null)
            Long sellerId,    // 주문 시점 스냅샷 (셀러별 정산 귀속, 미귀속이면 null)
            String productName,
            String size,
            long orderPrice,
            int quantity,
            long subtotal,
            long discountShare,      // 이 항목에 안분된 쿠폰 할인액(원). 실효가 = subtotal - discountShare
            OrderItemStatus status   // ACTIVE / CANCELLED(부분환불)
    ) {
        public static OrderItemResponse from(OrderItem item, long discountShare) {
            return new OrderItemResponse(
                    item.getId(),
                    item.getProductId(),
                    item.getOptionId(),
                    item.getBrandId(),
                    item.getSellerId(),
                    item.getProductName(),
                    item.getSize(),
                    item.getOrderPrice(),
                    item.getQuantity(),
                    item.getSubtotal(),
                    discountShare,
                    item.getStatus()
            );
        }
    }
}