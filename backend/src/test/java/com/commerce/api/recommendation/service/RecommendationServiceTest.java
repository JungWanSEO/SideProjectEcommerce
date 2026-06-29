package com.commerce.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import com.commerce.api.recommendation.dto.RecommendationResponse;
import com.commerce.api.recommendation.entity.Recommendation;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RecommendationService 단위 테스트 — 저장된 추천(personalized) vs 콜드스타트 인기순 폴백.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private RecommendationRepository recommendationRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductService productService;

    @InjectMocks private RecommendationService recommendationService;

    private static final Long MEMBER_ID = 1L;

    private ProductResponse productResponse(Long id) {
        return new ProductResponse(id, "p" + id, 10000L, null, null, ProductStatus.ON_SALE,
                1L, "상의", 1L, "Nike", List.of(), 0, 0.0, 0, LocalDateTime.now());
    }

    private Product popular(Long id) {
        Product p = Product.builder().name("p" + id).price(10000).status(ProductStatus.ON_SALE).build();
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    @DisplayName("저장된 추천이 있으면 personalized=true로 그 상품들을 반환")
    void getMyRecommendations_personalized() {
        given(recommendationRepository.findByMemberIdOrderByScoreDescProductIdAsc(MEMBER_ID))
                .willReturn(List.of(Recommendation.of(MEMBER_ID, 3L, 58.0)));
        given(productService.getProductMap(anyCollection())).willReturn(Map.of(3L, productResponse(3L)));

        RecommendationResponse response = recommendationService.getMyRecommendations(MEMBER_ID);

        assertThat(response.personalized()).isTrue();
        assertThat(response.products()).extracting(ProductResponse::id).containsExactly(3L);
    }

    @Test
    @DisplayName("저장된 추천이 없으면 콜드스타트 - 전체 인기순 폴백, personalized=false")
    void getMyRecommendations_coldStartFallback() {
        given(recommendationRepository.findByMemberIdOrderByScoreDescProductIdAsc(MEMBER_ID))
                .willReturn(List.of());
        given(productService.popularProductIds()).willReturn(List.of(7L));   // 인기 ID는 ProductService가 캐시
        given(productService.getProductMap(anyCollection())).willReturn(Map.of(7L, productResponse(7L)));

        RecommendationResponse response = recommendationService.getMyRecommendations(MEMBER_ID);

        assertThat(response.personalized()).isFalse();
        assertThat(response.products()).extracting(ProductResponse::id).containsExactly(7L);
    }
}
