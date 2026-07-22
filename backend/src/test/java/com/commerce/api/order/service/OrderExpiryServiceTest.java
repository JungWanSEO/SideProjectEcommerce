package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OrderExpiryService 단위 테스트 — 결제 대기(PENDING) 만료 배치.
 * TTL 경계는 리포지토리에 넘기는 <b>기준 시각</b>으로 검증한다(시계를 목킹하지 않고 캡처로 확인).
 */
@ExtendWith(MockitoExtension.class)
class OrderExpiryServiceTest {

    private static final int TTL_MINUTES = 30;

    @Mock private OrderRepository orderRepository;
    @Mock private com.commerce.api.coupon.service.MemberCouponService memberCouponService;
    @Mock private com.commerce.api.product.service.StockReservationService stockReservationService;

    @InjectMocks private OrderExpiryService orderExpiryService;

    @BeforeEach
    void setTtl() {
        // @Value는 단위 테스트에서 주입되지 않으므로 직접 심는다(운영 기본값과 동일).
        ReflectionTestUtils.setField(orderExpiryService, "pendingTtlMinutes", TTL_MINUTES);
    }

    private Order pendingOrder() {
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(10L).productName("반팔티셔츠").size("M")
                .orderPrice(10000L).quantity(1).build());
        return order;
    }

    @Test
    @DisplayName("만료 배치 - TTL이 지난 PENDING 주문을 취소하고 건수를 반환한다")
    void expirePendingOrders_cancelsExpired() {
        Order a = pendingOrder();
        Order b = pendingOrder();
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of(a, b));

        int cancelled = orderExpiryService.expirePendingOrders();

        assertThat(cancelled).isEqualTo(2);
        assertThat(a.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(b.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("만료 배치 - 쿠폰 적용된 주문이 만료되면 발급형 쿠폰을 복원한다(수동취소와 대칭)")
    void expirePendingOrders_releasesCoupon() {
        Order withCoupon = pendingOrder();
        withCoupon.applyCoupon("WELCOME5000", 5000L, "PLATFORM", null);   // 체크아웃 때 잠긴 쿠폰
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of(withCoupon));

        orderExpiryService.expirePendingOrders();

        assertThat(withCoupon.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(memberCouponService).release(100L, "WELCOME5000");   // 회원의 쿠폰을 미사용으로 되돌림
    }

    @Test
    @DisplayName("만료 배치 - 대상이 없으면 0건(불필요한 쓰기 없음)")
    void expirePendingOrders_noTargets() {
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of());

        assertThat(orderExpiryService.expirePendingOrders()).isZero();
    }

    @Test
    @DisplayName("만료 배치 - 기준 시각은 '지금 - TTL분'이고, PENDING만 대상으로 삼는다")
    void expirePendingOrders_usesTtlDeadlineAndOnlyPending() {
        given(orderRepository.findByStatusAndCreatedAtBefore(any(OrderStatus.class), any(LocalDateTime.class)))
                .willReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusMinutes(TTL_MINUTES);

        orderExpiryService.expirePendingOrders();

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), deadline.capture());
        // 결제된 주문을 건드리지 않는 근거 = 조회 자체가 PENDING으로 스코프됨(위 eq 검증)
        assertThat(deadline.getValue())
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(LocalDateTime.now().minusMinutes(TTL_MINUTES).plusSeconds(5));
    }

    @Test
    @DisplayName("만료 배치 - TTL 설정을 바꾸면 기준 시각도 따라 움직인다(운영 조정 가능)")
    void expirePendingOrders_respectsConfiguredTtl() {
        ReflectionTestUtils.setField(orderExpiryService, "pendingTtlMinutes", 120);
        given(orderRepository.findByStatusAndCreatedAtBefore(any(OrderStatus.class), any(LocalDateTime.class)))
                .willReturn(List.of());

        orderExpiryService.expirePendingOrders();

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), deadline.capture());
        assertThat(deadline.getValue()).isBefore(LocalDateTime.now().minusMinutes(119));
    }
}
