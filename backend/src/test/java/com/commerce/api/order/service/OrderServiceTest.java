package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.dto.OrderSearchCondition;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private com.commerce.api.product.service.StockReservationService stockReservationService;   // 항목별 재고 되돌리기(#2·#1)

    @InjectMocks
    private OrderService orderService;

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
    @DisplayName("주문 취소 성공(결제 완료) - 상태 CANCELLED + 취소 항목 재고 되돌리기")
    void cancel_success() {
        OrderItem line = item(3);
        Order order = orderWithId(1L, 100L, line);
        order.markPaid();
        ReflectionTestUtils.setField(line, "id", 500L);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.cancel(1L, 100L, false, null);   // 주문 주인(100번) 본인

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        // 재고 되돌리기는 항목별로 위임 — 복원/해제 판정(예약 상태)은 StockReservationService가 담당.
        verify(stockReservationService).undoForOrderItem(500L);
    }

    @Test
    @DisplayName("주문 취소 실패 - 이미 취소된 주문")
    void cancel_alreadyCancelled() {
        Order order = orderWithId(1L, 100L, item(3));
        order.cancel();
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 100L, false, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 취소된 주문");
    }

    @Test
    @DisplayName("주문 취소 - 미결제(PENDING) 주문도 취소 항목 재고를 항목별로 되돌린다(해제 판정은 예약 상태)")
    void cancel_pendingOrder_undoesReservation() {
        OrderItem line = item(3);
        Order order = orderWithId(1L, 100L, line);   // PENDING (결제 전)
        ReflectionTestUtils.setField(line, "id", 500L);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.cancel(1L, 100L, false, null);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(stockReservationService).undoForOrderItem(500L);
    }

    @Test
    @DisplayName("주문 취소(회귀) - 부분취소된(비활성) 항목은 재고를 이중 복원하지 않는다(이번 취소 항목만)")
    void cancel_afterPartialCancel_noDoubleStockRestore() {
        // 결제 완료된 2항목 주문에서 한 항목(500)을 먼저 항목취소해 비활성으로 만든다.
        //   이후 주문 전체 취소는 이번에 취소된 활성 항목(501)만 되돌려야 한다(500은 이미 그때 처리됨).
        OrderItem cancelledLine = OrderItem.builder()
                .productId(1L).optionId(10L).productName("반팔티셔츠").size("M")
                .orderPrice(10000L).quantity(3).build();
        OrderItem activeLine = OrderItem.builder()
                .productId(2L).optionId(20L).productName("맨투맨").size("L")
                .orderPrice(20000L).quantity(2).build();
        Order order = orderWithId(1L, 100L, cancelledLine, activeLine);
        order.markPaid();
        ReflectionTestUtils.setField(cancelledLine, "id", 500L);
        ReflectionTestUtils.setField(activeLine, "id", 501L);
        order.cancelItem(500L, 100L);   // 500 라인 비활성(주문은 rollup PAID 유지)

        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.cancel(1L, 100L, false, null);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(stockReservationService).undoForOrderItem(501L);              // 이번에 취소된 항목만
        verify(stockReservationService, never()).undoForOrderItem(500L);     // 이미 취소된 항목은 재차 안 함
    }

    private OrderItem itemOn(long optionId, int quantity) {
        return OrderItem.builder()
                .productId(1L).optionId(optionId).productName("t").size("M")
                .orderPrice(10000L).quantity(quantity).build();
    }

    @Test
    @DisplayName("항목 취소(미결제) - 그 항목 재고만 항목별로 되돌린다")
    void cancelItem_pending_undoesItemReservation() {
        OrderItem line1 = itemOn(10L, 2);
        OrderItem line2 = itemOn(20L, 3);
        Order order = orderWithId(1L, 100L, line1, line2);   // PENDING, 항목 2개
        ReflectionTestUtils.setField(line1, "id", 500L);
        ReflectionTestUtils.setField(line2, "id", 501L);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        orderService.cancelItem(1L, 500L, 100L, false, null);

        verify(stockReservationService).undoForOrderItem(500L);              // 그 항목만
        verify(stockReservationService, never()).undoForOrderItem(501L);
    }

    @Test
    @DisplayName("항목 취소(결제완료) - 그 항목 재고만 되돌린다(복원 판정은 예약 상태)")
    void cancelItem_paid_undoesItemStock() {
        OrderItem line1 = itemOn(10L, 2);
        OrderItem line2 = itemOn(20L, 3);
        Order order = orderWithId(1L, 100L, line1, line2);
        order.markPaid();
        ReflectionTestUtils.setField(line1, "id", 500L);
        ReflectionTestUtils.setField(line2, "id", 501L);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        orderService.cancelItem(1L, 500L, 100L, false, null);

        verify(stockReservationService).undoForOrderItem(500L);
        verify(stockReservationService, never()).undoForOrderItem(501L);
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
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 999L, false, null))
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
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.advanceShipping(1L, OrderStatus.SHIPPING, 1L, null, null);

        assertThat(response.status()).isEqualTo(OrderStatus.SHIPPING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    @DisplayName("배송 상태 전진 - SHIPPING→DELIVERED 성공")
    void advanceShipping_shippingToDelivered() {
        Order order = orderInStatus(1L, OrderStatus.SHIPPING);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.advanceShipping(1L, OrderStatus.DELIVERED, 1L, null, null);

        assertThat(response.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("배송 상태 전진 - SHIPPING 전이 시 택배사·운송장을 주문에 저장하고 타임라인에 남긴다")
    void advanceShipping_recordsCourierAndHistory() {
        Order order = orderInStatus(1L, OrderStatus.PAID);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.advanceShipping(
                1L, OrderStatus.SHIPPING, 9L, "CJ대한통운", "1234567890");

        // 송장은 이제 셀러별 shipment에 저장된다(#1 c안)
        assertThat(response.shipments()).anyMatch(
                s -> "CJ대한통운".equals(s.courier()) && "1234567890".equals(s.trackingNumber())
                        && s.status() == com.commerce.api.order.entity.ShipmentStatus.SHIPPING);
        // 주문 타임라인: 생성(PENDING) → PAID → SHIPPING(rollup·주체 9·송장 메모)
        assertThat(response.statusHistory()).extracting(r -> r.toStatus())
                .containsExactly(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.SHIPPING);
        var shipEvent = response.statusHistory().get(2);
        assertThat(shipEvent.fromStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(shipEvent.changedBy()).isEqualTo(9L);
        assertThat(shipEvent.memo()).isEqualTo("CJ대한통운 1234567890");
    }

    @Test
    @DisplayName("주문 취소 - 타임라인에 X→CANCELLED가 취소 주체와 함께 남는다")
    void cancel_recordsHistory() {
        Order order = orderInStatus(1L, OrderStatus.PAID);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.cancel(1L, 100L, false, null);   // 주인=100

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        var last = response.statusHistory().get(response.statusHistory().size() - 1);
        assertThat(last.fromStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(last.toStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(last.changedBy()).isEqualTo(100L);
        assertThat(last.memo()).isEqualTo("주문자 취소");
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - 건너뛰기(PAID→DELIVERED) 409, 상태 불변")
    void advanceShipping_skipForbidden() {
        Order order = orderInStatus(1L, OrderStatus.PAID);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.advanceShipping(1L, OrderStatus.DELIVERED, 1L, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("변경할 수 없습니다");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - 되돌리기(SHIPPING→PAID) 409")
    void advanceShipping_reverseForbidden() {
        Order order = orderInStatus(1L, OrderStatus.SHIPPING);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.advanceShipping(1L, OrderStatus.PAID, 1L, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("변경할 수 없습니다");
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - PENDING(미결제)에서 전진 409")
    void advanceShipping_fromPendingForbidden() {
        Order order = orderInStatus(1L, OrderStatus.PENDING);
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.advanceShipping(1L, OrderStatus.SHIPPING, 1L, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("변경할 수 없습니다");
    }

    @Test
    @DisplayName("배송 상태 전진 실패 - 없는 주문 404")
    void advanceShipping_notFound() {
        given(orderRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.advanceShipping(99L, OrderStatus.SHIPPING, 1L, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("주문 취소 실패 - 단일셀러 배송 시작(SHIPPING) 주문은 409, 재고 되돌리기 없음")
    void cancel_shippingBlocked() {
        Order order = orderInStatus(1L, OrderStatus.SHIPPING);   // 단일 항목·단일 shipment SHIPPING
        given(orderRepository.findByIdForUpdate(1L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(1L, 100L, false, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("배송이 시작");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING);
        verify(stockReservationService, never()).undoForOrderItem(any());   // 되돌리기 시도 없음
    }

    // ----- 어드민 주문 검색 -----

    @Test
    @DisplayName("어드민 주문 검색 - 조건을 QueryDSL search로 위임하고 요약으로 매핑한다")
    void searchOrders_delegatesToQueryDsl() {
        Pageable pageable = PageRequest.of(0, 20);
        OrderSearchCondition condition =
                new OrderSearchCondition("홍길동", null, OrderStatus.PAID, null, null, null, null, null);
        given(orderRepository.search(any(OrderSearchCondition.class), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(orderInStatus(1L, OrderStatus.PAID)), pageable, 1));

        PageResponse<OrderSummaryResponse> response = orderService.searchOrders(condition, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).status()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).search(condition, pageable);
    }

    @Test
    @DisplayName("셀러 주문 검색 - 요청 sellerId를 무시하고 로그인 셀러로 스코프를 덮어쓴다(남의 셀러 주문 차단)")
    void searchSellerOrders_forcesSellerScope() {
        Pageable pageable = PageRequest.of(0, 20);
        // 요청은 다른 셀러(999)를 노렸지만 서비스는 로그인 셀러(7)로 덮어써야 한다.
        OrderSearchCondition spoofed =
                new OrderSearchCondition(null, null, null, null, null, null, null, 999L);
        given(orderRepository.search(any(OrderSearchCondition.class), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        orderService.searchSellerOrders(7L, spoofed, pageable);

        ArgumentCaptor<OrderSearchCondition> captor = ArgumentCaptor.forClass(OrderSearchCondition.class);
        verify(orderRepository).search(captor.capture(), eq(pageable));
        assertThat(captor.getValue().sellerId()).isEqualTo(7L);   // 999가 아니라 로그인 셀러 7
    }
}
