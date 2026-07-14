package com.commerce.api.activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.entity.ActivityType;
import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * ActivityLogService 단위 테스트 (Mockito). 조회 기록 / 없는 상품 404 / 최근 본 상품(순서·제외·클램프).
 */
@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Mock private ActivityLogRepository activityLogRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductService productService;

    @InjectMocks private ActivityLogService activityLogService;

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 7L;

    @Test
    @DisplayName("조회 기록 성공 - 상품이 존재하면 VIEW 로그 1건 저장")
    void logView_success() {
        given(productRepository.existsById(PRODUCT_ID)).willReturn(true);

        activityLogService.logView(MEMBER_ID, PRODUCT_ID);

        ArgumentCaptor<ActivityLog> captor = ArgumentCaptor.forClass(ActivityLog.class);
        verify(activityLogRepository).save(captor.capture());
        ActivityLog saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        org.assertj.core.api.Assertions.assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    @DisplayName("조회 기록 실패 - 없는 상품이면 404, 저장 안 함")
    void logView_productNotFound() {
        given(productRepository.existsById(PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> activityLogService.logView(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(activityLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("최근 본 상품 - 리포지토리가 준 최신순을 그대로 유지해 응답한다")
    void getRecentlyViewed_keepsRecentOrder() {
        givenRecentIds(30L, 10L, 20L);
        givenProductsOnSale(10L, 20L, 30L);

        List<ProductResponse> result = activityLogService.getRecentlyViewed(MEMBER_ID, 8, null);

        assertThat(result).extracting(ProductResponse::id).containsExactly(30L, 10L, 20L);
    }

    @Test
    @DisplayName("최근 본 상품 - 판매중지·삭제된 상품과 exclude 대상은 빠진다")
    void getRecentlyViewed_filtersHiddenAndExcluded() {
        givenRecentIds(10L, 20L, 30L, 40L);
        given(productService.getProductMap(anyCollection())).willReturn(Map.of(
                10L, product(10L, ProductStatus.DISCONTINUED),   // 판매중지 → 제외
                30L, product(30L, ProductStatus.ON_SALE),        // exclude 대상 → 제외
                40L, product(40L, ProductStatus.SOLD_OUT)));     // 품절은 노출(다시 살 수도 있으니)
        // 20L은 맵에 없음 = 그 사이 삭제된 상품 → 제외

        List<ProductResponse> result = activityLogService.getRecentlyViewed(MEMBER_ID, 8, 30L);

        assertThat(result).extracting(ProductResponse::id).containsExactly(40L);
    }

    @Test
    @DisplayName("최근 본 상품 - limit은 1~20으로 클램프하고, 걸러질 것을 감안해 후보를 3배로 조회한다")
    void getRecentlyViewed_clampsLimitAndOverFetches() {
        givenRecentIds();   // 이력 없음 → 빈 목록
        given(productService.getProductMap(anyCollection())).willReturn(Map.of());

        assertThat(activityLogService.getRecentlyViewed(MEMBER_ID, 100, null)).isEmpty();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(activityLogRepository)
                .findRecentlyViewedProductIds(eq(MEMBER_ID), eq(ActivityType.VIEW), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20 * 3);   // 상한 20 → 후보 60
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    // === 헬퍼 ===

    private void givenRecentIds(Long... productIds) {
        given(activityLogRepository.findRecentlyViewedProductIds(
                eq(MEMBER_ID), eq(ActivityType.VIEW), any(Pageable.class)))
                .willReturn(List.of(productIds));
    }

    /** 주어진 상품들을 전부 판매중(ON_SALE)으로 enrich되게 스텁. */
    private void givenProductsOnSale(Long... productIds) {
        given(productService.getProductMap(anyCollection())).willReturn(Stream.of(productIds)
                .collect(Collectors.toMap(Function.identity(), id -> product(id, ProductStatus.ON_SALE))));
    }

    private ProductResponse product(Long id, ProductStatus status) {
        return new ProductResponse(id, "상품" + id, 10000L, "설명", null, status,
                null, null, null, null, List.of(), 0, 0.0, 0, LocalDateTime.now());
    }
}
