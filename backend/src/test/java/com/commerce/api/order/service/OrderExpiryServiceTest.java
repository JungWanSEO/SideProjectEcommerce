package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.order.entity.Order;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OrderExpiryService 단위 테스트 — 만료 후보를 훑어 주문별로 worker에 위임(오케스트레이션).
 * 주문별 취소 로직은 {@link OrderExpiryWorker}가 담당하므로 여기선 위임·스킵·기준시각만 본다.
 */
@ExtendWith(MockitoExtension.class)
class OrderExpiryServiceTest {

    private static final int TTL_MINUTES = 30;

    @Mock private OrderRepository orderRepository;
    @Mock private OrderExpiryWorker worker;

    @InjectMocks private OrderExpiryService orderExpiryService;

    @BeforeEach
    void setTtl() {
        ReflectionTestUtils.setField(orderExpiryService, "pendingTtlMinutes", TTL_MINUTES);
    }

    private Order pendingOrder(Long id) {
        Order order = Order.create(100L);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    @DisplayName("만료 배치 - 만료 PENDING마다 worker.expireOne을 호출하고 취소 건수를 반환한다")
    void expirePendingOrders_delegatesPerOrder() {
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of(pendingOrder(1L), pendingOrder(2L)));

        int cancelled = orderExpiryService.expirePendingOrders();

        assertThat(cancelled).isEqualTo(2);
        verify(worker).expireOne(1L);
        verify(worker).expireOne(2L);
    }

    @Test
    @DisplayName("만료 배치 - 한 주문이 결제와 경합(낙관락 충돌)하면 그 주문만 스킵하고 나머지는 계속 만료한다")
    void expirePendingOrders_skipsRacingOrder() {
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of(pendingOrder(1L), pendingOrder(2L)));
        willThrow(new ObjectOptimisticLockingFailureException(Order.class, 1L)).given(worker).expireOne(1L);

        int cancelled = orderExpiryService.expirePendingOrders();

        assertThat(cancelled).isEqualTo(1);   // 1은 경합으로 스킵, 2만 취소
        verify(worker).expireOne(2L);
    }

    @Test
    @DisplayName("만료 배치 - 대상이 없으면 0건, worker 미호출")
    void expirePendingOrders_noTargets() {
        given(orderRepository.findByStatusAndCreatedAtBefore(any(OrderStatus.class), any(LocalDateTime.class)))
                .willReturn(List.of());

        assertThat(orderExpiryService.expirePendingOrders()).isZero();
        verify(worker, never()).expireOne(any());
    }

    @Test
    @DisplayName("만료 배치 - 기준 시각은 '지금 - TTL분'이고 PENDING만 대상으로 삼는다")
    void expirePendingOrders_usesTtlDeadlineAndOnlyPending() {
        given(orderRepository.findByStatusAndCreatedAtBefore(any(OrderStatus.class), any(LocalDateTime.class)))
                .willReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusMinutes(TTL_MINUTES);

        orderExpiryService.expirePendingOrders();

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), deadline.capture());
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
