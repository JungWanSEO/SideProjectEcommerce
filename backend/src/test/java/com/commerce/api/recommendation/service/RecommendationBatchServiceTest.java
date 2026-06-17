package com.commerce.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.entity.Recommendation;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import com.commerce.api.wishlist.entity.Wishlist;
import com.commerce.api.wishlist.repository.WishlistRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
 * RecommendationBatchService 단위 테스트 — 친화도(카테고리/브랜드) 기반 추천, 보유 제외, 무관 상품 제외.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationBatchServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private WishlistRepository wishlistRepository;
    @Mock private ActivityLogRepository activityLogRepository;
    @Mock private ProductRepository productRepository;
    @Mock private RecommendationRepository recommendationRepository;

    @InjectMocks private RecommendationBatchService batchService;

    @Captor private ArgumentCaptor<List<Recommendation>> recsCaptor;

    private Product product(Long id, Long categoryId, Long brandId) {
        Product p = Product.builder()
                .name("p" + id).price(10000).status(ProductStatus.ON_SALE)
                .categoryId(categoryId).brandId(brandId).build();
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private Wishlist wish(Long memberId, Long productId) {
        return Wishlist.builder().memberId(memberId).productId(productId).build();
    }

    @Test
    @DisplayName("추천 - 선호 카테고리/브랜드의 미보유 상품만, 무관 상품·찜한 상품은 제외")
    void run_recommendsAffineNonOwned() {
        // member 1: 찜 p1(cat10·brand100, ×2), 조회 p2(cat10·brand200, ×1)
        given(orderRepository.findByStatusIn(OrderStatus.PURCHASED)).willReturn(List.of());
        given(wishlistRepository.findAll()).willReturn(List.of(wish(1L, 1L)));
        given(activityLogRepository.findByCreatedAtAfter(any())).willReturn(List.of(ActivityLog.view(1L, 2L)));
        Product p1 = product(1L, 10L, 100L);   // 찜함 → 제외
        Product p2 = product(2L, 10L, 200L);   // 조회함(찜·구매 아님 → 후보 유지), cat10 친화
        Product p3 = product(3L, 10L, 100L);   // 후보: cat10 + brand100 친화 최고
        Product p4 = product(4L, 99L, 999L);   // 무관 → 친화도 0 → 제외
        given(productRepository.findAll()).willReturn(List.of(p1, p2, p3, p4));

        int total = batchService.run();

        verify(recommendationRepository).deleteByMemberId(1L);
        verify(recommendationRepository).saveAll(recsCaptor.capture());
        List<Recommendation> recs = recsCaptor.getValue();
        Set<Long> recommended = recs.stream().map(Recommendation::getProductId).collect(Collectors.toSet());
        assertThat(recommended).contains(2L, 3L).doesNotContain(1L, 4L);
        // 친화도: p3(cat10=3 + brand100=2 =5) > p2(cat10=3 + brand200=1 =4) → p3가 상위
        assertThat(recs.get(0).getProductId()).isEqualTo(3L);
        assertThat(total).isEqualTo(recs.size());
    }

    @Test
    @DisplayName("신호 없는 회원은 추천을 만들지 않는다(콜드스타트는 읽기에서 폴백)")
    void run_noSignals_noRecommendation() {
        given(orderRepository.findByStatusIn(OrderStatus.PURCHASED)).willReturn(List.of());
        given(wishlistRepository.findAll()).willReturn(List.of());
        given(activityLogRepository.findByCreatedAtAfter(any())).willReturn(List.of());
        given(productRepository.findAll()).willReturn(List.of(product(1L, 10L, 100L)));

        int total = batchService.run();

        assertThat(total).isZero();
    }
}
