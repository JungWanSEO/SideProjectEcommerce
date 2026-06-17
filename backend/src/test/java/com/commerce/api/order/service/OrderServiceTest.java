package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OrderService 단위 테스트 — 조회/취소. (생성은 OrderProcessor로 분리 → OrderProcessorTest에서 검증)
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderProcessor orderProcessor;   // create는 위임만 — 여기선 미사용
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private OrderService orderService;

    /** 옵션(id=10, "M", 주어진 stock) 1개를 가진 상품 — 취소 시 옵션 재고 복원 검증용. */
    private Product productWithOption(Long productId, Long optionId, int stock) {
        Product product = Product.builder()
                .name("반팔티셔츠").price(10000L).description("desc").status(ProductStatus.ON_SALE).build();
        ReflectionTestUtils.setField(product, "id", productId);
        ProductOption option = ProductOption.create("M", stock);
        ReflectionTestUtils.setField(option, "id", optionId);
        product.addOption(option);
        return product;
    }

    /** 옵션 10번("M")을 산 주문 항목. */
    private OrderItem item(int quantity) {
        return OrderItem.builder()
                .productId(1L).optionId(10L).productName("반팔티셔츠").size("M")
                .orderPrice(10000L).quantity(quantity).build();
    }

    private Order orderWithId(Long id, Long memberId, OrderItem... items) {
        Order order = Order.create(memberId);
        for (OrderItem it : items) {
            order.addItem(it);
        }
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    @DisplayName("주문 취소 성공 - 상태 CANCELLED + 옵션 재고 복원")
    void cancel_success() {
        Order order = orderWithId(1L, 100L, item(3));
        order.markPaid();   // 결제 완료 주문이어야 취소 시 재고가 복원됨
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        Product product = productWithOption(1L, 10L, 7);   // 결제로 7까지 차감된 옵션
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));

        OrderResponse response = orderService.cancel(1L, 100L, false);   // 주문 주인(100번) 본인

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getOptions().get(0).getStock()).isEqualTo(10);   // 7 + 3 복원
    }

    @Test
    @DisplayName("주문 취소 실패 - 이미 취소된 주문")
    void cancel_alreadyCancelled() {
        Order order = orderWithId(1L, 100L, item(3));
        order.cancel();
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 100L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 취소된 주문");
    }

    @Test
    @DisplayName("주문 취소 - 미결제(PENDING) 주문은 재고 복원 없이 취소된다")
    void cancel_pendingOrder_noStockRestore() {
        Order order = orderWithId(1L, 100L, item(3));   // PENDING (결제 전)
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.cancel(1L, 100L, false);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(productRepository, never()).findByOptionId(any());   // 재고 복원 시도 없음
    }

    @Test
    @DisplayName("주문 조회 실패 - 없는 주문이면 예외")
    void getOrder_notFound() {
        given(orderRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(999L, 1L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("주문을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("내 주문 목록 - Page를 요약(OrderSummaryResponse)으로 매핑한다 (대표상품명·항목수)")
    void getMyOrders_mapsToSummary() {
        Order order = orderWithId(1L, 100L, item(3));
        Pageable pageable = PageRequest.of(0, 20);
        given(orderRepository.findByMemberId(100L, pageable))
                .willReturn(new PageImpl<>(List.of(order), pageable, 1));

        PageResponse<OrderSummaryResponse> response = orderService.getMyOrders(100L, pageable);

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);
        OrderSummaryResponse summary = response.content().get(0);
        assertThat(summary.totalPrice()).isEqualTo(30000L);            // 10000 * 3
        assertThat(summary.representativeProductName()).isEqualTo("반팔티셔츠");
        assertThat(summary.itemCount()).isEqualTo(1);                  // 라인 1개
    }

    @Test
    @DisplayName("주문 조회 - 본인 주문이면 성공")
    void getOrder_ownerCanView() {
        Order order = orderWithId(1L, 100L, item(1));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(1L, 100L, false);

        assertThat(response.memberId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("주문 조회 - 남의 주문이면 403 (ADMIN 아님)")
    void getOrder_nonOwnerForbidden() {
        Order order = orderWithId(1L, 100L, item(1));   // 주문 주인 = 100번
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getOrder(1L, 999L, false))   // 999번이 조회 시도
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 주문");
    }

    @Test
    @DisplayName("주문 조회 - ADMIN이면 남의 주문도 조회 가능")
    void getOrder_adminCanViewOthers() {
        Order order = orderWithId(1L, 100L, item(1));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(1L, 999L, true);   // 999번이지만 ADMIN

        assertThat(response.memberId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("주문 취소 - 남의 주문이면 403, 취소·재고복원 일어나지 않음")
    void cancel_nonOwnerForbidden() {
        Order order = orderWithId(1L, 100L, item(3));
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 999L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 주문");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);   // 취소 막힘 → 상태 그대로
    }

    // ----- 배송 상태 전진 (forward-only) -----

    /** 실제 상태머신을 따라 원하는 상태까지 전진시킨 주문(주인=100번, 항목 1개). */
    private Order orderInStatus(Long id, OrderStatus status) {
        Order order = orderWithId(id, 100L, item(1));
        if (status == OrderStatus.PENDING) {
            return order;
        }
        order.markPaid();                                 // PENDING → PAID
        if (status == OrderStatus.PAID) {
            return order;
        }
        if (status == OrderStatus.CANCELLED) {
            order.cancel();
            return order;
        }
        order.advanceShipping(OrderStatus.SHIPPING);      // PAID → SHIPPING
        if (status == OrderStatus.SHIPPING) {
            return order;
        }
        order.advanceShipping(OrderStatus.DELIVERED);     // SHIPPING → DELIVERED
        return order;
    }

    @Test
    @DisplayName("배송 상태 전진 - PAID→SHIPPING 성공")
    void advanceShipping_paidToShipping() {
        Order order = orderInStatus(1L, OrderStatus.PAID);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.advanceShipping(1L, OrderStatus.SHIPPING);

        assertThat(response.status()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("배송 상태 전진 - SHIPPING→DELIVERED 성공")
    void advanceShipping_shippingToDelivered() {
        Order order = orderInStatus(1L, OrderStatus.SHIPPING);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.advanceShipping(1L, OrderStatus.DELIVERED);

        assertThat(response.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - 건너뛰기(PAID→DELIVERED) 409, 상태 불변")
    void advanceShipping_skipForbidden() {
        Order order = orderInStatus(1L, OrderStatus.PAID);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.advanceShipping(1L, OrderStatus.DELIVERED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("변경할 수 없습니다");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - 되돌리기(SHIPPING→PAID) 409")
    void advanceShipping_reverseForbidden() {
        Order order = orderInStatus(1L, OrderStatus.SHIPPING);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.advanceShipping(1L, OrderStatus.PAID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("변경할 수 없습니다");
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - PENDING(미결제)에서 전진 409")
    void advanceShipping_fromPendingForbidden() {
        Order order = orderInStatus(1L, OrderStatus.PENDING);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.advanceShipping(1L, OrderStatus.SHIPPING))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("변경할 수 없습니다");
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - 없는 주문 404")
    void advanceShipping_notFound() {
        given(orderRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.advanceShipping(99L, OrderStatus.SHIPPING))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("주문 취소 실패 - 배송 시작(SHIPPING) 주문은 409, 재고 복원 없음")
    void cancel_shippingBlocked() {
        Order order = orderInStatus(1L, OrderStatus.SHIPPING);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 100L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("배송이 시작된");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
        verify(productRepository, never()).findByOptionId(any());
    }

    // ----- 어드민 전체 주문 목록 -----

    @Test
    @DisplayName("어드민 주문 목록 - status 없으면 findAll(전체)")
    void getAllOrders_all() {
        Pageable pageable = PageRequest.of(0, 20);
        given(orderRepository.findAll(pageable))
                .willReturn(new PageImpl<>(List.of(orderInStatus(1L, OrderStatus.PAID)), pageable, 1));

        PageResponse<OrderSummaryResponse> response = orderService.getAllOrders(null, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).status()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).findAll(pageable);
    }

    @Test
    @DisplayName("어드민 주문 목록 - status 있으면 그 상태만(findByStatus)")
    void getAllOrders_byStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        given(orderRepository.findByStatus(OrderStatus.SHIPPING, pageable))
                .willReturn(new PageImpl<>(List.of(orderInStatus(1L, OrderStatus.SHIPPING)), pageable, 1));

        PageResponse<OrderSummaryResponse> response =
                orderService.getAllOrders(OrderStatus.SHIPPING, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).status()).isEqualTo(OrderStatus.SHIPPING);
        verify(orderRepository).findByStatus(OrderStatus.SHIPPING, pageable);
    }
}
