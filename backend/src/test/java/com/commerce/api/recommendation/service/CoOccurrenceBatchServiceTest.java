package com.commerce.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.entity.ProductCoOccurrence;
import com.commerce.api.recommendation.repository.ProductCoOccurrenceRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CoOccurrenceBatchService 단위 테스트 — 함께 산 빈도 집계, 자기 자신·취소 항목·비 ON_SALE 후보 제외.
 */
@ExtendWith(MockitoExtension.class)
class CoOccurrenceBatchServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductCoOccurrenceRepository coOccurrenceRepository;

    @InjectMocks private CoOccurrenceBatchService batchService;

    @Captor private ArgumentCaptor<List<ProductCoOccurrence>> rowsCaptor;

    private Product product(Long id, ProductStatus status) {
        Product p = Product.builder().name("p" + id).price(10000).status(status).build();
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private OrderItem activeItem(Long productId, long optionId) {
        return OrderItem.builder()
                .productId(productId).optionId(optionId)
                .productName("p" + productId).size("FREE").orderPrice(10000).quantity(1).build();
    }

    /** 주어진 상품들로 구성된 PAID 주문(항목은 모두 ACTIVE). */
    private Order paidOrder(Long memberId, Long... productIds) {
        Order order = Order.create(memberId);
        long opt = 1;
        for (Long pid : productIds) {
            order.addItem(activeItem(pid, opt++));
        }
        order.markPaid();
        return order;
    }

    /** 캡처된 모든 saveAll 인자를 한 리스트로 평탄화. */
    private List<ProductCoOccurrence> allSavedRows() {
        verify(coOccurrenceRepository, atLeastOnce()).saveAll(rowsCaptor.capture());
        return rowsCaptor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("함께 산 상품 - 같은 주문의 다른 상품을 함께 산 빈도순으로(자기 자신 제외)")
    void run_ranksCoBoughtByFrequency() {
        // order1: p1·p2·p3, order2: p1·p2 → 기준 p1 입장에서 p2는 2회, p3는 1회 함께 샀다
        given(orderRepository.findByStatus(OrderStatus.PAID))
                .willReturn(List.of(paidOrder(1L, 1L, 2L, 3L), paidOrder(2L, 1L, 2L)));
        given(productRepository.findAll()).willReturn(List.of(
                product(1L, ProductStatus.ON_SALE),
                product(2L, ProductStatus.ON_SALE),
                product(3L, ProductStatus.ON_SALE)));

        int total = batchService.run();

        verify(coOccurrenceRepository).deleteAllInBatch();
        List<ProductCoOccurrence> all = allSavedRows();
        // 기준 p1의 추천: p2(2회) 먼저, p3(1회) 다음, 자기 자신은 없음
        List<ProductCoOccurrence> forP1 = all.stream()
                .filter(r -> r.getReferenceProductId().equals(1L)).toList();
        assertThat(forP1).extracting(ProductCoOccurrence::getProductId).containsExactly(2L, 3L);
        assertThat(forP1.get(0).getCoBuyCount()).isEqualTo(2);
        assertThat(forP1.get(1).getCoBuyCount()).isEqualTo(1);
        assertThat(forP1).noneMatch(r -> r.getProductId().equals(1L));
        assertThat(total).isEqualTo(all.size());
    }

    @Test
    @DisplayName("취소된 항목과 ON_SALE이 아닌 후보는 함께 산 상품에서 제외")
    void run_excludesCancelledItemAndNonOnSaleCandidate() {
        // 주문: p1·p2 활성, p3 취소. 후보 p2는 SOLD_OUT.
        Order order = Order.create(1L);
        order.addItem(activeItem(1L, 1));
        order.addItem(activeItem(2L, 2));
        OrderItem cancelled = activeItem(3L, 3);
        cancelled.cancel();
        order.addItem(cancelled);
        order.markPaid();
        given(orderRepository.findByStatus(OrderStatus.PAID)).willReturn(List.of(order));
        given(productRepository.findAll()).willReturn(List.of(
                product(1L, ProductStatus.ON_SALE),
                product(2L, ProductStatus.SOLD_OUT),
                product(3L, ProductStatus.ON_SALE)));

        batchService.run();

        List<ProductCoOccurrence> all = allSavedRows();
        // p3(취소)는 어떤 쌍에도 없음(기준으로도, 추천으로도)
        assertThat(all).noneMatch(r -> r.getReferenceProductId().equals(3L) || r.getProductId().equals(3L));
        // p2(SOLD_OUT)는 추천 후보로 제외
        assertThat(all).noneMatch(r -> r.getProductId().equals(2L));
        // 기준 p1의 유일한 활성 쌍이 p2(SOLD_OUT)뿐 → p1 추천 없음
        assertThat(all).noneMatch(r -> r.getReferenceProductId().equals(1L));
    }

    @Test
    @DisplayName("단일 항목 주문만 있으면 쌍이 없어 함께 산 상품을 만들지 않는다")
    void run_singleItemOrders_noPairs() {
        given(orderRepository.findByStatus(OrderStatus.PAID))
                .willReturn(List.of(paidOrder(1L, 1L), paidOrder(2L, 2L)));
        given(productRepository.findAll()).willReturn(List.of(
                product(1L, ProductStatus.ON_SALE), product(2L, ProductStatus.ON_SALE)));

        int total = batchService.run();

        assertThat(total).isZero();
        verify(coOccurrenceRepository).deleteAllInBatch();
        verify(coOccurrenceRepository, never()).saveAll(any());
    }
}
