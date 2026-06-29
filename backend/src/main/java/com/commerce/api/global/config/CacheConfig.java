package com.commerce.api.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 캐시 설정 — Spring Cache 추상화 + (Caffeine | Redis) 병행.
 *
 * <p><b>왜 추상화인가:</b> {@code @Cacheable}/{@code @CacheEvict}는 구현체에 무관하다. 어떤 CacheManager가
 * 활성화되든 서비스 코드는 그대로다 — 여기 설정만 바꿔 로컬 인메모리(Caffeine)와 분산(Redis)을 오간다.
 *
 * <p><b>토글(RabbitMQ outbox.publisher 와 같은 병행 opt-in 패턴):</b>
 * <ul>
 *   <li>{@code app.cache.enabled=false} → {@link NoOpCacheManager}(캐시 사실상 끔 — 테스트 기본).</li>
 *   <li>{@code app.cache.provider=caffeine}(기본) → 로컬 인메모리 Caffeine. 외부 인프라 0.</li>
 *   <li>{@code app.cache.provider=redis} → 분산 캐시 Redis(다중 인스턴스가 캐시를 공유). docker-compose redis 필요.</li>
 * </ul>
 *
 * <p><b>캐시별 정책</b>(이름은 상수 — 어노테이션에서 참조): {@link #PRODUCT_DETAIL}(10분)·
 * {@link #CATEGORY_LIST}/{@link #BRAND_LIST}(1시간). Caffeine은 추가로 최대 크기를, 둘 다 변경 시 evict로 무효화.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCT_DETAIL = "productDetail";
    public static final String CATEGORY_LIST = "categoryList";
    public static final String BRAND_LIST = "brandList";
    public static final String POPULAR_PRODUCTS = "popularProducts";

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);
    private static final Duration LIST_TTL = Duration.ofHours(1);
    private static final Duration POPULAR_TTL = Duration.ofMinutes(5);   // 인기 순위는 천천히 변함 — 짧은 TTL로 자가 수렴

    /**
     * 로컬 인메모리(Caffeine) — 기본. 캐시 켜짐 && provider != redis 일 때.
     * recordStats(): 적중/미스/축출 통계 기록 → 적중률 모니터링(/api/monitoring/caches·Actuator)의 전제.
     */
    @Bean
    @ConditionalOnExpression("${app.cache.enabled:true} and !'${app.cache.provider:caffeine}'.equals('redis')")
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);   // 없는 상품은 예외(404)라 null을 캐시할 일이 없다.
        manager.registerCustomCache(PRODUCT_DETAIL, Caffeine.newBuilder()
                .recordStats().maximumSize(1_000).expireAfterWrite(PRODUCT_TTL).build());
        manager.registerCustomCache(CATEGORY_LIST, Caffeine.newBuilder()
                .recordStats().maximumSize(1).expireAfterWrite(LIST_TTL).build());
        manager.registerCustomCache(BRAND_LIST, Caffeine.newBuilder()
                .recordStats().maximumSize(1).expireAfterWrite(LIST_TTL).build());
        manager.registerCustomCache(POPULAR_PRODUCTS, Caffeine.newBuilder()
                .recordStats().maximumSize(1).expireAfterWrite(POPULAR_TTL).build());
        return manager;
    }

    /**
     * 분산 캐시(Redis) — 캐시 켜짐 && provider == redis 일 때. 다중 인스턴스가 같은 캐시를 공유한다.
     * Caffeine과 달리 값을 <b>직렬화</b>해 저장하므로 JSON 직렬화기를 명시한다(아래).
     */
    @Bean
    @ConditionalOnExpression("${app.cache.enabled:true} and '${app.cache.provider:caffeine}'.equals('redis')")
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(redisValueSerializer()))
                .entryTtl(PRODUCT_TTL);
        Map<String, RedisCacheConfiguration> perCache = Map.of(
                PRODUCT_DETAIL, base.entryTtl(PRODUCT_TTL),
                CATEGORY_LIST, base.entryTtl(LIST_TTL),
                BRAND_LIST, base.entryTtl(LIST_TTL),
                POPULAR_PRODUCTS, base.entryTtl(POPULAR_TTL));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    /** 테스트 등에서 캐시를 끄는 경로 — 모든 캐시 연산이 no-op(@Cacheable이 매번 메서드 실행). */
    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "false")
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }

    /**
     * Redis 값 JSON 직렬화기. 캐시 값은 구체 타입(레코드 DTO·{@code List})이라 역직렬화 때 타입을 알도록
     * {@code @class} 메타를 심는다(DefaultTyping.EVERYTHING — 레코드는 final이라 NON_FINAL로는 타입 누락).
     * LocalDateTime 등 java.time은 JavaTimeModule로 처리(타임스탬프 대신 ISO 문자열).
     */
    @SuppressWarnings("deprecation")   // DefaultTyping.EVERYTHING: 레코드(final) 타입까지 @class를 심어야 역직렬화 가능
    private static GenericJackson2JsonRedisSerializer redisValueSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
