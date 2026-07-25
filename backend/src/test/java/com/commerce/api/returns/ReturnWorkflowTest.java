package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.service.ReturnService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품/교환 워크플로 통합 테스트(#3 P3) — 요청·승인·수거·검수 전이 + 자격 게이트(ACTIVE·배송완료·기한·중복) + 인가(IDOR).
 * 돈·재고 이동은 P4/P6 — 여기선 상태 전이만.
 */
@SpringBootTest
@Transactional
class ReturnWorkflowTest {

    @Autowired private ReturnService returnService;
    @Autowired private OrderRepository orderRepository;

    private OrderItem item(Long sellerId, long price) {
        return OrderItem.builder().productId(sellerId == null ? 99L : sellerId).optionId(11L).sellerId(sellerId)
                .productName("P").size("M").orderPrice(price).quantity(1).build();
    }

    /** 셀러1 항목이 배송완료(DELIVERED)된 주문을 저장. */
    private Order deliveredOrder() {
        Order order = Order.create(100L);
        order.addItem(item(1L, 5000L));
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1");
        order.advanceShipping(OrderStatus.DELIVERED, null, null, null);   // deliveredAt 세팅
        return orderRepository.saveAndFlush(order);
    }

    private ReturnStatusUpdateRequest action(ReturnAction a) {
        return new ReturnStatusUpdateRequest(a, null);
    }

    @Test
    @DisplayName("정상 워크플로 - 요청→승인→수거→검수 (셀러 처리)")
    void happyPath() {
        Order order = deliveredOrder();
        long itemId = order.getOrderItems().get(0).getId();

        ReturnResponse req = returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "단순 변심", null));
        assertThat(req.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(req.sellerId()).isEqualTo(1L);   // 서버가 항목에서 도출

        Long returnId = req.id();
        assertThat(returnService.advanceForSeller(returnId, 1L, action(ReturnAction.APPROVE), 7L).status())
                .isEqualTo(ReturnStatus.APPROVED);
        assertThat(returnService.advanceForSeller(returnId, 1L, action(ReturnAction.PICK_UP), 7L).status())
                .isEqualTo(ReturnStatus.PICKED_UP);
        assertThat(returnService.advanceForSeller(returnId, 1L, action(ReturnAction.INSPECT), 7L).status())
                .isEqualTo(ReturnStatus.INSPECTED);
    }

    @Test
    @DisplayName("자격 - 배송 완료 안 된 항목은 반품 불가(409)")
    void notDelivered() {
        Order order = Order.create(100L);
        order.addItem(item(1L, 5000L));
        order.markPaid();   // PAID(미배송)
        Order saved = orderRepository.saveAndFlush(order);
        long itemId = saved.getOrderItems().get(0).getId();

        assertThatThrownBy(() -> returnService.create(100L, false, saved.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "x", null)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("자격(HIGH) - 이미 취소된 항목은 배송완료 게이트를 통과해도 반품 불가(과다환불 차단)")
    void cancelledItemBlocked() {
        // 셀러1 항목 2개(A,B). A를 출고 전 취소 → 셀러 shipment는 B 때문에 살아남아 DELIVERED까지 진행.
        Order order = Order.create(100L);
        OrderItem a = item(1L, 5000L);
        OrderItem b = item(1L, 4000L);
        order.addItem(a);
        order.addItem(b);
        order.markPaid();
        orderRepository.saveAndFlush(order);
        long aId = a.getId();
        order.cancelItem(aId, 100L);   // A 취소(shipment PAID라 가능), shipment는 B로 살아남음
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1");
        order.advanceShipping(OrderStatus.DELIVERED, null, null, null);
        orderRepository.saveAndFlush(order);

        // A(CANCELLED)로 반품 요청 → 배송완료 게이트는 통과하지만 ACTIVE 게이트에서 409
        assertThatThrownBy(() -> returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(aId, ReturnType.RETURN, "x", null)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("자격 - 같은 항목에 진행 중 반품이 있으면 중복 409")
    void duplicateBlocked() {
        Order order = deliveredOrder();
        long itemId = order.getOrderItems().get(0).getId();
        returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "1차", null));

        assertThatThrownBy(() -> returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "2차", null)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("인가 - 남의 주문 반품 요청 403, 남의 셀러 처리 403, ADMIN 주문불일치 404")
    void authorization() {
        Order order = deliveredOrder();
        long itemId = order.getOrderItems().get(0).getId();

        // 남의 주문(멤버 999) 반품 요청 → 403
        assertThatThrownBy(() -> returnService.create(999L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "x", null)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        ReturnResponse req = returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "x", null));

        // 셀러2가 셀러1 반품 처리 → 403
        assertThatThrownBy(() -> returnService.advanceForSeller(req.id(), 2L, action(ReturnAction.APPROVE), 2L))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        // ADMIN이 다른 주문번호로 이 반품 지목 → 404
        assertThatThrownBy(() -> returnService.advanceForAdmin(order.getId() + 999L, req.id(), action(ReturnAction.APPROVE), 1L))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("교환 확정 - 존재하지 않는 교환 옵션이면 404(옵션 스왑 정합, 스왑·재출고 성립 테스트는 ReturnExchangeTest)")
    void completeUnknownOption() {
        Order order = deliveredOrder();
        long itemId = order.getOrderItems().get(0).getId();
        ReturnResponse req = returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.EXCHANGE, "사이즈 교환", 999_999L));   // 없는 옵션
        returnService.advanceForSeller(req.id(), 1L, action(ReturnAction.APPROVE), 1L);
        returnService.advanceForSeller(req.id(), 1L, action(ReturnAction.PICK_UP), 1L);
        returnService.advanceForSeller(req.id(), 1L, action(ReturnAction.INSPECT), 1L);

        assertThatThrownBy(() -> returnService.advanceForSeller(req.id(), 1L, action(ReturnAction.COMPLETE), 1L))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
