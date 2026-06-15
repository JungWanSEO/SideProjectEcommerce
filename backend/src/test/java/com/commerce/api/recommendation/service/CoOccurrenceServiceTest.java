package com.commerce.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import com.commerce.api.recommendation.dto.CoOccurrenceResponse;
import com.commerce.api.recommendation.entity.ProductCoOccurrence;
import com.commerce.api.recommendation.repository.ProductCoOccurrenceRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CoOccurrenceService 단위 테스트 — 저장된 함께 산 상품(cooccurrence=true) vs 카테고리/브랜드 폴백(false) vs 404.
 */
@ExtendWith(MockitoExtension.class)
class CoOccurrenceServiceTest {

    @Mock private ProductCoOccurrenceRepository coOccurrenceRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductService productService;

    @InjectMocks private CoOccurrenceService coOccurrenceService;

    private static final Long REF_ID = 1L;

    private Product product(Long id, Long categoryId, Long brandId) {
        Product p = Product.builder().name("p" + id).price(10000).status(ProductStatus.ON_SALE)
                .categoryId(categoryId).brandId(brandId).build();
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private ProductResponse productResponse(Long id) {
        return new ProductResponse(id, "p" + id, 10000L, null, null, ProductStatus.ON_SALE,
                1L, "상의", 1L, "Nike", List.of(), 0, 0.0, 0, LocalDateTime.now());
    }

    @Test
    @DisplayName("함께 산 데이터가 있으면 cooccurrence=true로 그 상품들을 점수순으로 반환")
    void getCoOccurrence_present() {
        given(productRepository.findById(REF_ID)).willReturn(Optional.of(product(REF_ID, 10L, 100L)));
        given(coOccurrenceRepository.findByReferenceProductIdOrderByScoreDescProductIdAsc(eq(REF_ID), any()))
                .willReturn(List.of(
                        ProductCoOccurrence.of(REF_ID, 2L, 3, 30.0),
                        ProductCoOccurrence.of(REF_ID, 3L, 1, 10.0)));
        given(productService.getProductMap(anyCollection()))
                .willReturn(Map.of(2L, productResponse(2L), 3L, productResponse(3L)));

        CoOccurrenceResponse response = coOccurrenceService.getCoOccurrence(REF_ID, 8);

        assertThat(response.cooccurrence()).isTrue();
        assertThat(response.products()).extracting(ProductResponse::id).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("함께 산 데이터가 없으면 같은 카테고리/브랜드 폴백, cooccurrence=false")
    void getCoOccurrence_fallback() {
        given(productRepository.findById(REF_ID)).willReturn(Optional.of(product(REF_ID, 10L, 100L)));
        given(coOccurrenceRepository.findByReferenceProductIdOrderByScoreDescProductIdAsc(eq(REF_ID), any()))
                .willReturn(List.of());
        given(productRepository.findCoOccurrenceFallback(any(), any(), any(), any(), any()))
                .willReturn(List.of(product(5L, 10L, 100L)));
        given(productService.getProductMap(anyCollection())).willReturn(Map.of(5L, productResponse(5L)));

        CoOccurrenceResponse response = coOccurrenceService.getCoOccurrence(REF_ID, 8);

        assertThat(response.cooccurrence()).isFalse();
        assertThat(response.products()).extracting(ProductResponse::id).containsExactly(5L);
    }

    @Test
    @DisplayName("없는 상품이면 404")
    void getCoOccurrence_notFound() {
        given(productRepository.findById(REF_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> coOccurrenceService.getCoOccurrence(REF_ID, 8))
                .isInstanceOf(BusinessException.class);
    }
}
