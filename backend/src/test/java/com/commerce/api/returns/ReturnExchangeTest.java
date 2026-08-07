package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderItemStatus;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.ShipmentKind;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.repository.ShipmentRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.StockReservationService;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.service.ReturnService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교환(#3 P6) 통합 테스트 — 옵션 스왑·양방향 재고·EXCHANGE 재출고·revenue-neutral·대체품 품절 롤백.
 */
@SpringBootTest
@Transactional
class ReturnExchangeTest {

    @Autowired private ReturnService returnService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StockReservationService stockReservationService;
    @Autowired private EntityManager em;

    private ReturnStatusUpdateRequest act(ReturnAction a) {
        return new ReturnStatusUpdateRequest(a, null);
    }

    private int stockOf(long optionId) {
        em.flush();
        em.clear();   // @Modifying UPDATE(reserve/consume/restore) 후 fresh 재조회
        return em.find(ProductOption.class, optionId).getStock();
    }

    /** 상품(옵션 M·L stock) + 셀러1 단일 항목(옵션 M) 배송완료 주문 + M 옵션 CONSUMED 예약. */
    private long[] setup(int stockM, int stockL) {
        Product product = Product.builder().name("셔츠").price(5000L).description("d").status(ProductStatus.ON_SALE).build();
        product.addOption(ProductOption.create("M", stockM));
        product.addOption(ProductOption.create("L", stockL));
        Product savedP = productRepository.save(product);
        long optionM = savedP.getOptions().get(0).getId();
        long optionL = savedP.getOptions().get(1).getId();

        Order order = Order.create(100L);
        order.addItem(OrderItem.builder().productId(savedP.getId()).optionId(optionM).sellerId(1L)
                .productName("셔츠").size("M").orderPrice(5000L).quantity(1).build());
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1");
        order.advanceShipping(OrderStatus.DELIVERED, null, null, null);
        Order savedO = orderRepository.saveAndFlush(order);
        long orderId = savedO.getId();
        long itemId = savedO.getOrderItems().get(0).getId();

        // M 옵션 예약→소진(CONSUMED, stock 차감) — 실제 결제 흐름 모사
        stockReservationService.reserve(orderId, itemId, optionM, 1, LocalDateTime.now().plusMinutes(30));
        stockReservationService.consumeForOrder(orderId);

        return new long[] { orderId, itemId, optionM, optionL };
    }

    private ReturnResponse toInspectedExchange(long orderId, long itemId, long exchangeOptionId) {
        ReturnResponse req = returnService.create(100L, false, orderId,
                new ReturnCreateRequest(itemId, ReturnType.EXCHANGE, "사이즈 교환", null, exchangeOptionId));
        returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.APPROVE), 1L);
        returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.PICK_UP), 1L);
        returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.INSPECT), 1L);
        return req;
    }

    @Test
    @DisplayName("교환 e2e - 옵션 스왑(항목 ACTIVE 유지)·양방향 재고·EXCHANGE 재출고·주문 DELIVERED 유지")
    void exchange_e2e() {
        long[] ids = setup(5, 5);
        long orderId = ids[0], itemId = ids[1], optionM = ids[2], optionL = ids[3];
        assertThat(stockOf(optionM)).isEqualTo(4);   // 소진됨
        assertThat(stockOf(optionL)).isEqualTo(5);

        ReturnResponse req = toInspectedExchange(orderId, itemId, optionL);
        ReturnResponse done = returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.COMPLETE), 1L);

        assertThat(done.status()).isEqualTo(ReturnStatus.COMPLETED);
        // 옵션 스왑: 원 항목 ACTIVE 유지·optionId/size만 교체(revenue-neutral)
        Order after = orderRepository.findById(orderId).orElseThrow();
        OrderItem item = after.requireItem(itemId);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.ACTIVE);
        assertThat(item.getOptionId()).isEqualTo(optionL);
        assertThat(item.getSize()).isEqualTo("L");
        assertThat(item.getSubtotal()).isEqualTo(5000L);   // 금액 불변
        assertThat(after.getStatus()).isEqualTo(OrderStatus.DELIVERED);   // 교환 재출고는 rollup 제외 → 후퇴 없음
        // EXCHANGE 재출고 shipment 생성(PAID, kind=EXCHANGE)
        assertThat(shipmentRepository.findByOrderId(orderId))
                .anyMatch(s -> s.getKind() == ShipmentKind.EXCHANGE && s.getStatus() == ShipmentStatus.PAID);
        // 양방향 재고: 원품 M 복원(+1=5), 대체품 L 소진(−1=4)
        assertThat(stockOf(optionM)).isEqualTo(5);
        assertThat(stockOf(optionL)).isEqualTo(4);
    }

    @Test
    @DisplayName("교환 대체품 품절 - 409로 전체 롤백(원품·옵션 미변경, 자동 환불 전환 없음)")
    void exchange_outOfStock() {
        // 신청 시점엔 L이 1개 남아 통과하고, 검수 사이에 그 1개가 빠져 확정에서 품절이 되는 경로.
        // 신청 시점 검증(가용재고)은 스냅샷이라 이 레이스를 막지 못한다 — 진짜 게이트는 확정의 원자적 소진이다.
        long[] ids = setup(5, 1);
        long orderId = ids[0], itemId = ids[1], optionM = ids[2], optionL = ids[3];
        ReturnResponse req = toInspectedExchange(orderId, itemId, optionL);

        em.createQuery("update ProductOption o set o.stock = 0 where o.id = :id")
                .setParameter("id", optionL).executeUpdate();   // 그 사이 다른 주문이 마지막 1개를 가져감
        em.clear();

        assertThatThrownBy(() -> returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.COMPLETE), 1L))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
        // 품절은 consumeForExchange 첫 단계라 원 항목/옵션 미변경
        assertThat(orderRepository.findById(orderId).orElseThrow().requireItem(itemId).getOptionId()).isEqualTo(optionM);
    }

    @Test
    @DisplayName("교환 신청 - 대체 옵션이 품절이면 신청 단계에서 409(수거·검수까지 갔다가 막히지 않게)")
    void createExchange_soldOutOption() {
        long[] ids = setup(5, 0);   // 대체품 L 재고 0
        long orderId = ids[0], itemId = ids[1], optionL = ids[3];

        assertThatThrownBy(() -> returnService.create(100L, false, orderId,
                new ReturnCreateRequest(itemId, ReturnType.EXCHANGE, "사이즈 교환", null, optionL)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("교환 신청 - 현재와 같은 옵션이면 400(신청 단계에서 차단)")
    void createExchange_sameOption() {
        long[] ids = setup(5, 5);
        long orderId = ids[0], itemId = ids[1], optionM = ids[2];

        assertThatThrownBy(() -> returnService.create(100L, false, orderId,
                new ReturnCreateRequest(itemId, ReturnType.EXCHANGE, "사이즈 교환", null, optionM)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("교환 완료 후 같은 항목 재-반품 차단(409) - 교환품 수령+환불 이중지급 방지(적대적리뷰 MED)")
    void exchange_thenReturn_blocked() {
        long[] ids = setup(5, 5);
        long orderId = ids[0], itemId = ids[1], optionL = ids[3];
        ReturnResponse req = toInspectedExchange(orderId, itemId, optionL);
        returnService.advanceForSeller(req.id(), 1L, act(ReturnAction.COMPLETE), 1L);   // 교환 COMPLETED

        // 원 항목은 ACTIVE·원배송 DELIVERED라 자격 게이트는 통과하지만, 교환완료 가드가 재-반품을 막아야 한다
        assertThatThrownBy(() -> returnService.create(100L, false, orderId,
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "재반품 시도", null, null)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("교환 검증 - 다른 상품 옵션으로는 교환 불가(400, 신청 단계에서 차단)")
    void exchange_differentProduct() {
        long[] ids = setup(5, 5);
        long orderId = ids[0], itemId = ids[1];
        // 다른 상품의 옵션
        Product other = Product.builder().name("바지").price(9000L).description("d").status(ProductStatus.ON_SALE).build();
        other.addOption(ProductOption.create("FREE", 5));
        long otherOption = productRepository.save(other).getOptions().get(0).getId();

        // 신청·확정이 같은 규칙(requireExchangeOption)을 쓰므로, 규칙 위반은 더 이른 신청 단계에서 걸린다
        assertThatThrownBy(() -> returnService.create(100L, false, orderId,
                new ReturnCreateRequest(itemId, ReturnType.EXCHANGE, "사이즈 교환", null, otherOption)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
