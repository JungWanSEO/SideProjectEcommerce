package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.service.StockReservationService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OrderExpiryWorker 단위 테스트 — 만료 주문 한 건의 취소(쿠폰 복원·예약 해제) + 멱등 스킵.
 */
@ExtendWith(MockitoExtension.class)
class OrderExpiryWorkerTest {

    @Mock private OrderRepository orderRepository;
    @Mock private MemberCouponService memberCouponService;
    @Mock private StockReservationService stockReservationService;

    @InjectMocks private OrderExpiryWorker worker;

    private Order pendingOrder(Long id, String couponCode) {
        Order order = Order.create(100L);
        ReflectionTestUtils.setField(order, "id", id);
        if (couponCode != null) {
            order.applyCoupon(couponCode, 0L, "PLATFORM", null);   // 총액 0이라 할인 0(가드 통과)
        }
        return order;
    }

    @Test
    @DisplayName("PENDING이면 취소 + 쿠폰 복원 + 예약 해제")
    void expireOne_pending() {
        Order order = pendingOrder(1L, "WELCOME5000");
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        worker.expireOne(1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(memberCouponService).release(100L, "WELCOME5000");   // 쿠폰 복원(수동취소와 대칭)
        verify(stockReservationService).releaseForOrder(1L);        // 예약 해제
    }

    @Test
    @DisplayName("그 사이 결제돼 PENDING이 아니면 아무것도 하지 않는다(멱등·상태 뒤집힘 방지)")
    void expireOne_notPending_skips() {
        Order order = pendingOrder(1L, null);
        order.markPaid();   // 결제됨(PAID)
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        worker.expireOne(1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);   // 취소로 뒤집지 않음
        verify(stockReservationService, never()).releaseForOrder(any());
        verify(memberCouponService, never()).release(any(), any());
    }

    @Test
    @DisplayName("주문이 없으면 스킵")
    void expireOne_notFound_skips() {
        given(orderRepository.findById(9L)).willReturn(Optional.empty());

        worker.expireOne(9L);

        verify(stockReservationService, never()).releaseForOrder(any());
    }
}
