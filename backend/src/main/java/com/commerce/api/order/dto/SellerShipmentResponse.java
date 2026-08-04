package com.commerce.api.order.dto;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderItemStatus;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.entity.ShippingInfo;
import java.util.List;
import java.util.Objects;

/**
 * 셀러 출고 응답(#1 리뷰 #5 교정) — 셀러가 자기 배송 건을 전진했을 때 돌려주는 <b>셀러 스코프</b> 뷰.
 *
 * <p>멀티셀러 주문은 여러 셀러의 항목·배송을 한 애그리거트에 담으므로, 전체 {@link OrderResponse}를 그대로 주면
 * 셀러가 <b>경쟁 셀러의 품목·가격·운송장과 구매자 식별자</b>까지 보게 된다(테넌트 격리 위반). 그래서 이 DTO는
 * 요청 셀러의 shipment·그 셀러 항목·배송지(출고에 필요)만 노출하고, 주문 총액·할인·쿠폰·구매자 memberId·타 셀러
 * 항목/배송은 제외한다.
 */
public record SellerShipmentResponse(
        Long orderId,
        Long shipmentId,
        Long sellerId,             // null = 플랫폼 직매입(셀러 경로에선 도달 불가 — advanceForSeller가 403)
        ShipmentStatus status,
        com.commerce.api.order.entity.ShipmentKind kind,   // ORIGINAL(원배송) / EXCHANGE(교환 재출고) — 목록에서 구분해야 셀러가 무엇을 보내는지 안다
        java.time.LocalDateTime deliveredAt,               // 배송완료 시각(반품 기한 기산점) — 없으면 null
        String courier,
        String trackingNumber,
        List<SellerLine> items,    // 이 셀러의 항목만(무엇을 포장해 보낼지)
        ShippingView shipping      // 배송지 — 출고에 필요(수령인·주소·연락처)
) {
    public static SellerShipmentResponse of(Order order, Shipment shipment) {
        List<SellerLine> lines = order.getOrderItems().stream()
                .filter(i -> Objects.equals(i.getSellerId(), shipment.getSellerId()))
                .map(SellerLine::from)
                .toList();
        return new SellerShipmentResponse(
                order.getId(),
                shipment.getId(),
                shipment.getSellerId(),
                shipment.getStatus(),
                shipment.getKind(),
                shipment.getDeliveredAt(),
                shipment.getCourier(),
                shipment.getTrackingNumber(),
                lines,
                ShippingView.from(order.getShippingInfo()));
    }

    /** 이 셀러가 보낼 항목(가격·할인 등 매출 정보는 셀러 정산 화면 소관이라 여기선 제외). */
    public record SellerLine(
            Long orderItemId,
            Long productId,
            Long optionId,
            String productName,
            String size,
            int quantity,
            OrderItemStatus status
    ) {
        static SellerLine from(OrderItem item) {
            return new SellerLine(item.getId(), item.getProductId(), item.getOptionId(),
                    item.getProductName(), item.getSize(), item.getQuantity(), item.getStatus());
        }
    }

    /** 배송지(출고에 필요) — 주문 시점 스냅샷. 없으면 null. */
    public record ShippingView(
            String recipient,
            String phone,
            String zipcode,
            String address1,
            String address2,
            String deliveryMemo
    ) {
        static ShippingView from(ShippingInfo s) {
            if (s == null || s.getRecipient() == null) {
                return null;
            }
            return new ShippingView(s.getRecipient(), s.getPhone(), s.getZipcode(),
                    s.getAddress1(), s.getAddress2(), s.getDeliveryMemo());
        }
    }
}
