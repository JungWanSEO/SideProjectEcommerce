package com.commerce.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.dto.SellerShipmentResponse;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.repository.ShipmentRepository;
import com.commerce.api.order.service.ShipmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * shipment 전진 인가 통합 테스트(#1 P5) — 셀러 소유권/ADMIN 주문 매칭을 워커가 트랜잭션 안에서 강제하는지 검증.
 * 셀러는 자기 shipment만, 플랫폼(null) 버킷은 ADMIN만. IDOR(남의 셀러 배송 이동) 차단.
 */
@SpringBootTest
class ShipmentAdvanceAuthTest {

    @Autowired private ShipmentService shipmentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShipmentRepository shipmentRepository;

    /** 셀러1·셀러2·플랫폼(null) 3버킷이 섞인 PAID 주문을 저장한다. */
    private Order persistThreeBucketOrder() {
        Order order = Order.create(100L);
        order.addItem(item(1L));
        order.addItem(item(2L));
        order.addItem(item(null));   // 플랫폼 직매입
        order.markPaid();
        return orderRepository.saveAndFlush(order);
    }

    private OrderItem item(Long sellerId) {
        long productId = (sellerId == null) ? 99L : sellerId;   // 셀러별 상품 구분 → 스코프 필터 실검증
        return OrderItem.builder()
                .productId(productId).optionId(1L).sellerId(sellerId)
                .productName("P").size("M").orderPrice(5000L).quantity(1).build();
    }

    private Shipment shipmentOf(Long orderId, Long sellerId) {
        return shipmentRepository.findByOrderId(orderId).stream()
                .filter(s -> s.belongsToSeller(sellerId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("셀러 전진 - 자기 shipment는 성공, 남의 셀러·플랫폼(null) 버킷은 403")
    void seller_ownOk_othersForbidden() {
        Order order = persistThreeBucketOrder();
        Long orderId = order.getId();
        Long myShip = shipmentOf(orderId, 1L).getId();
        Long otherShip = shipmentOf(orderId, 2L).getId();
        Long platformShip = shipmentOf(orderId, null).getId();

        // 셀러1이 자기 것 전진 → 성공
        shipmentService.advanceForSeller(myShip, 1L, ShipmentStatus.SHIPPING, 500L, "CJ", "1");
        assertThat(shipmentRepository.findById(myShip).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.SHIPPING);

        // 셀러1이 셀러2 배송을 전진 시도 → 403(IDOR)
        assertThatThrownBy(() -> shipmentService.advanceForSeller(otherShip, 1L, ShipmentStatus.SHIPPING, 500L, "CJ", "2"))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        // 셀러1이 플랫폼(null) 배송을 전진 시도 → 403
        assertThatThrownBy(() -> shipmentService.advanceForSeller(platformShip, 1L, ShipmentStatus.SHIPPING, 500L, "CJ", "3"))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ADMIN 전진 - 플랫폼(null) 버킷 포함 성공, 경로 주문과 안 맞으면 404")
    void admin_anyBucketOk_orderMismatch404() {
        Order order = persistThreeBucketOrder();
        Long orderId = order.getId();
        Long platformShip = shipmentOf(orderId, null).getId();

        // ADMIN이 플랫폼 배송 전진 → 성공
        shipmentService.advanceForAdmin(orderId, platformShip, ShipmentStatus.SHIPPING, 1L, "CJ", "9");
        assertThat(shipmentRepository.findById(platformShip).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.SHIPPING);

        // 다른 주문번호로 이 배송을 지목 → 404(경로 불일치)
        assertThatThrownBy(() -> shipmentService.advanceForAdmin(orderId + 999L, platformShip, ShipmentStatus.DELIVERED, 1L, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("셀러 부분 출고 → 셀러 스코프 응답(내 shipment·항목만) + 주문 rollup SHIPPING")
    void seller_partialShip_scopedResponseAndRollup() {
        Order order = persistThreeBucketOrder();
        Long orderId = order.getId();

        var response = shipmentService.advanceForSeller(
                shipmentOf(orderId, 1L).getId(), 1L, ShipmentStatus.SHIPPING, 500L, "CJ", "1");

        // 셀러 스코프 응답: 내 shipment 상태·송장 + 내 항목만(타 셀러·구매자 memberId·주문총액 없음 — 리뷰 #5)
        assertThat(response.sellerId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(ShipmentStatus.SHIPPING);
        assertThat(response.courier()).isEqualTo("CJ");
        assertThat(response.items()).extracting(SellerShipmentResponse.SellerLine::productId).containsOnly(1L);

        // 주문 전체 rollup: 셀러1만 출고해도 SHIPPING
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPING);
    }
}
