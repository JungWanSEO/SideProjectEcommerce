package com.commerce.api.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 설정 — Spring Cache 추상화 + Caffeine(로컬 인메모리).
 *
 * <p><b>왜 추상화 위에 Caffeine인가:</b> {@code @Cacheable}/{@code @CacheEvict}는 구현체에 무관하다.
 * 지금은 외부 인프라 0인 Caffeine을 쓰고, 다중 인스턴스 운영(인스턴스마다 캐시가 갈라지면 안 되는 상황)이
 * 되면 <b>이 설정만</b> Redis CacheManager로 바꾸면 된다(애플리케이션 코드 무변경).
 *
 * <p><b>캐시별 정책</b>(이름은 상수로 고정 — 어노테이션에서 참조):
 * <ul>
 *   <li>{@link #PRODUCT_DETAIL} — 상품 상세. 가장 많이 읽힘. 쓰기(수정/상태/옵션/이미지)·리뷰/찜 카운터 변동 시 무효화.</li>
 *   <li>{@link #CATEGORY_LIST}/{@link #BRAND_LIST} — 거의 안 바뀌는 분류 목록. 변경(추가/수정/삭제) 시에만 무효화.</li>
 * </ul>
 *
 * <p><b>테스트 격리</b>: 캐시가 켜지면 "쓰고 바로 읽기"류 테스트가 stale로 깨질 수 있다. 그래서
 * {@code app.cache.enabled=false}(테스트 기본)면 {@link NoOpCacheManager}로 캐시를 사실상 끈다.
 * 전용 캐시 테스트만 이 값을 true로 켜서 적중/무효화를 검증한다(운영 기본은 켜짐 — matchIfMissing).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCT_DETAIL = "productDetail";
    public static final String CATEGORY_LIST = "categoryList";
    public static final String BRAND_LIST = "brandList";

    /** 운영 기본: Caffeine. 캐시별로 만료/최대크기를 따로 준다(목록은 작고 길게, 상세는 크고 짧게). */
    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);   // 없는 상품은 예외(404)라 null을 캐시할 일이 없다.
        manager.registerCustomCache(PRODUCT_DETAIL, Caffeine.newBuilder()
                .maximumSize(1_000).expireAfterWrite(Duration.ofMinutes(10)).build());
        manager.registerCustomCache(CATEGORY_LIST, Caffeine.newBuilder()
                .maximumSize(1).expireAfterWrite(Duration.ofHours(1)).build());
        manager.registerCustomCache(BRAND_LIST, Caffeine.newBuilder()
                .maximumSize(1).expireAfterWrite(Duration.ofHours(1)).build());
        return manager;
    }

    /** 테스트 등에서 캐시를 끄는 경로 — 모든 캐시 연산이 no-op(@Cacheable이 매번 메서드 실행). */
    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "false")
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }
}
