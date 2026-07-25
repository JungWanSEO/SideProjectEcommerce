package com.commerce.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 자격 쿼리(OrderRepository.hasActivePurchase) 통합 테스트 — #3 교정.
 * 반품(RETURNED)·취소(CANCELLED)한 항목은 "구매 완료" 주문이라도 리뷰 자격을 주지 않아야 한다.
 */
@SpringBootTest
@Transactional
class ReviewEligibilityQueryTest {

    private static final long MEMBER = 100L;
    private static final long PRODUCT = 7L;

    @Autowired private OrderRepository orderRepository;

    private OrderItem item(int status) {
        OrderItem it = OrderItem.builder().productId(PRODUCT).optionId(1L).sellerId(1L)
                .productName("셔츠").size("M").orderPrice(5000L).quantity(1).build();
        if (status == 1) {
            it.cancel();
        } else if (status == 2) {
            it.markReturned();
        }
        return it;
    }

    /** status: 0=ACTIVE, 1=CANCELLED, 2=RETURNED */
    private void savePurchasedOrderWith(int status) {
        Order order = Order.create(MEMBER);
        order.addItem(item(status));
        order.markPaid();   // → PAID(=PURCHASED 집합)
        orderRepository.saveAndFlush(order);
    }

    @Test
    @DisplayName("ACTIVE 항목 구매 → 리뷰 자격 있음")
    void activeItem_eligible() {
        savePurchasedOrderWith(0);
        assertThat(orderRepository.hasActivePurchase(MEMBER, OrderStatus.PURCHASED, PRODUCT)).isTrue();
    }

    @Test
    @DisplayName("반품(RETURNED)한 항목뿐이면 → 리뷰 자격 없음")
    void returnedItem_notEligible() {
        savePurchasedOrderWith(2);
        assertThat(orderRepository.hasActivePurchase(MEMBER, OrderStatus.PURCHASED, PRODUCT)).isFalse();
    }

    @Test
    @DisplayName("취소(CANCELLED)한 항목뿐이면 → 리뷰 자격 없음")
    void cancelledItem_notEligible() {
        savePurchasedOrderWith(1);
        assertThat(orderRepository.hasActivePurchase(MEMBER, OrderStatus.PURCHASED, PRODUCT)).isFalse();
    }

    @Test
    @DisplayName("같은 상품을 두 번 사서 하나는 반품·하나는 ACTIVE → 자격 있음")
    void oneActiveAmongReturned_eligible() {
        savePurchasedOrderWith(2);   // 반품분
        savePurchasedOrderWith(0);   // ACTIVE분
        assertThat(orderRepository.hasActivePurchase(MEMBER, OrderStatus.PURCHASED, PRODUCT)).isTrue();
    }
}
