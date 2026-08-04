package com.commerce.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.order.dto.SellerShipmentResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.entity.ShippingInfo;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.service.ShipmentService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 배송 목록(셀러 콘솔 "출고 관리") 검증 — <b>스코프</b>가 전부다.
 *
 * <p>전진 API는 소유권을 트랜잭션 안에서 재검증하지만(ShipmentAdvanceAuthTest), 목록은 애초에
 * <b>쿼리가 sellerId로 좁혀</b> 남의 배송이 응답에 실릴 수 없어야 한다(조회 IDOR 차단). 응답 DTO도
 * 전진과 같은 셀러 스코프라 <b>타 셀러 품목이 섞이지 않는지</b>까지 함께 본다.
 */
@SpringBootTest
@Transactional
class SellerShipmentListTest {

    private static final long SELLER_A = 8001L;
    private static final long SELLER_B = 8002L;

    @Autowired private ShipmentService shipmentService;
    @Autowired private OrderRepository orderRepository;

    /** 셀러A·셀러B·플랫폼(null) 3버킷이 섞인 PAID 주문 — 결제 팬아웃으로 shipment 3건이 생긴다. */
    private Order persistMixedOrder() {
        Order order = Order.create(700L);
        order.addItem(item(SELLER_A, "A상품"));
        order.addItem(item(SELLER_B, "B상품"));
        order.addItem(item(null, "플랫폼상품"));
        order.ship(ShippingInfo.of("김구매", "010-1111-2222", "06236", "서울 강남구", "101호", null));
        order.markPaid();
        return orderRepository.saveAndFlush(order);
    }

    private OrderItem item(Long sellerId, String name) {
        return OrderItem.builder()
                .productId(sellerId == null ? 99L : sellerId).optionId(1L).sellerId(sellerId)
                .productName(name).size("M").orderPrice(10_000L).quantity(1).build();
    }

    private List<SellerShipmentResponse> listOf(long sellerId, ShipmentStatus status) {
        PageResponse<SellerShipmentResponse> page =
                shipmentService.getSellerShipments(sellerId, status, PageRequest.of(0, 20));
        return page.content();
    }

    @Test
    @DisplayName("내 배송만 보인다 — 남의 셀러·플랫폼 버킷은 목록에서 제외(조회 IDOR 차단)")
    void listsOnlyOwnShipments() {
        persistMixedOrder();

        List<SellerShipmentResponse> mine = listOf(SELLER_A, null);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).sellerId()).isEqualTo(SELLER_A);
        // 응답의 항목도 내 셀러 것만 — 같은 주문에 있는 타 셀러/플랫폼 품목이 새어나가면 안 된다
        assertThat(mine.get(0).items()).hasSize(1);
        assertThat(mine.get(0).items().get(0).productName()).isEqualTo("A상품");
    }

    @Test
    @DisplayName("출고에 필요한 것은 준다 — 배송지·전진 대상 shipmentId·종류(원배송/교환)")
    void exposesWhatSellerNeedsToShip() {
        Order order = persistMixedOrder();

        SellerShipmentResponse mine = listOf(SELLER_A, null).get(0);

        assertThat(mine.orderId()).isEqualTo(order.getId());
        assertThat(mine.shipmentId()).isNotNull();          // 이 id로 전진 API를 호출한다(목록이 없으면 알 수 없었다)
        assertThat(mine.status()).isEqualTo(ShipmentStatus.PAID);
        assertThat(mine.kind()).isEqualTo(com.commerce.api.order.entity.ShipmentKind.ORIGINAL);
        assertThat(mine.shipping()).isNotNull();
        assertThat(mine.shipping().recipient()).isEqualTo("김구매");
    }

    @Test
    @DisplayName("상태 필터 — 출고 대기(PAID)만 골라 본다")
    void filtersByStatus() {
        Order order = persistMixedOrder();
        // 셀러A만 출고 시작 → A=SHIPPING, B=PAID
        long shipmentId = listOf(SELLER_A, null).get(0).shipmentId();
        shipmentService.advanceForSeller(shipmentId, SELLER_A, ShipmentStatus.SHIPPING, null, "CJ", "123");

        assertThat(listOf(SELLER_A, ShipmentStatus.PAID)).isEmpty();
        assertThat(listOf(SELLER_A, ShipmentStatus.SHIPPING)).hasSize(1);
        assertThat(listOf(SELLER_B, ShipmentStatus.PAID)).hasSize(1);   // 남의 전진에 영향받지 않는다
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.SHIPPING);   // 주문 상태는 shipment rollup 파생
    }
}
