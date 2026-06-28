package com.commerce.api.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commerce.api.category.dto.CategoryCreateRequest;
import com.commerce.api.category.repository.CategoryRepository;
import com.commerce.api.category.service.CategoryService;
import com.commerce.api.global.config.CacheConfig;
import com.commerce.api.monitoring.dto.CacheStatsResponse;
import com.commerce.api.monitoring.service.CacheMonitoringService;
import com.commerce.api.product.dto.ProductStatusUpdateRequest;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import com.commerce.api.wishlist.service.WishlistService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 캐시 적중·무효화 검증 — 이 클래스만 {@code app.cache.enabled=true}로 캐시를 켠다(다른 테스트는 NoOp).
 *
 * <p>두 가지를 본다: (1) 같은 키를 두 번 읽으면 두 번째는 캐시에서 나와 DB 조회가 안 일어남(스파이 호출 수),
 * (2) 쓰기가 일어나면 해당 캐시가 비워짐(무효화). 상품 상세는 쓰기뿐 아니라 <b>찜(다른 도메인)</b> 변경에도
 * 무효화되는 교차 도메인 무효화를 확인한다.
 */
@SpringBootTest
@TestPropertySource(properties = "app.cache.enabled=true")
class CacheTest {

    @Autowired private ProductService productService;
    @Autowired private CategoryService categoryService;
    @Autowired private WishlistService wishlistService;
    @Autowired private CacheManager cacheManager;
    @Autowired private CacheMonitoringService cacheMonitoringService;

    @MockitoSpyBean private ProductRepository productRepository;
    @MockitoSpyBean private CategoryRepository categoryRepository;

    private static final AtomicLong SEQ = new AtomicLong();
    private Long productId;

    @BeforeEach
    void setUp() {
        productId = productRepository.save(Product.builder()
                .name("캐시상품").price(10000L).description("d").status(ProductStatus.ON_SALE).build()).getId();
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());   // 메서드 간 격리
        clearInvocations(productRepository, categoryRepository);
    }

    @Test
    @DisplayName("상품 상세 - 두 번째 조회는 캐시에서(DB 조회 1회), 캐시에 적재된다")
    void productDetail_servedFromCache() {
        productService.getProduct(productId);
        productService.getProduct(productId);

        verify(productRepository, times(1)).findById(productId);   // 2번째는 캐시 → DB 조회 안 함
        assertThat(productCache().get(productId)).isNotNull();
    }

    @Test
    @DisplayName("상품 상세 - 상태 변경 시 캐시 무효화")
    void productDetail_evictedOnWrite() {
        productService.getProduct(productId);
        assertThat(productCache().get(productId)).isNotNull();

        productService.changeStatus(productId, new ProductStatusUpdateRequest(ProductStatus.SOLD_OUT));

        assertThat(productCache().get(productId)).isNull();        // evict 됨
    }

    @Test
    @DisplayName("상품 상세 - 찜 추가(다른 도메인) 시에도 무효화(교차 도메인)")
    void productDetail_evictedOnWishlist() {
        productService.getProduct(productId);
        assertThat(productCache().get(productId)).isNotNull();

        wishlistService.add(7777L, productId);   // 찜 카운터가 바뀜 → 상품 상세 캐시 무효화

        assertThat(productCache().get(productId)).isNull();
    }

    @Test
    @DisplayName("카테고리 목록 - 캐시 적중 + 변경 시 무효화")
    void categoryList_cachedAndEvicted() {
        categoryService.getCategories();
        categoryService.getCategories();
        verify(categoryRepository, times(1)).findAll();   // 2번째는 캐시

        categoryService.create(new CategoryCreateRequest("캐시카테고리-" + SEQ.incrementAndGet(), null)); // evict

        categoryService.getCategories();
        verify(categoryRepository, times(2)).findAll();   // 무효화돼 다시 조회
    }

    @Test
    @DisplayName("캐시 적중률 통계 - 조회 시 hit/miss가 기록된다(recordStats)")
    void cacheStats_recordHitsAndMisses() {
        CacheStatsResponse before = productDetailStats();
        productService.getProduct(productId);   // miss (setUp에서 엔트리 비움)
        productService.getProduct(productId);   // hit
        productService.getProduct(productId);   // hit
        CacheStatsResponse after = productDetailStats();

        assertThat(after.hitCount() - before.hitCount()).isGreaterThanOrEqualTo(2);
        assertThat(after.missCount() - before.missCount()).isGreaterThanOrEqualTo(1);
        assertThat(after.hitRate()).isBetween(0.0, 1.0);
    }

    private CacheStatsResponse productDetailStats() {
        return cacheMonitoringService.getCacheStats().stream()
                .filter(s -> s.cacheName().equals(CacheConfig.PRODUCT_DETAIL))
                .findFirst().orElseThrow();
    }

    private Cache productCache() {
        return cacheManager.getCache(CacheConfig.PRODUCT_DETAIL);
    }
}
