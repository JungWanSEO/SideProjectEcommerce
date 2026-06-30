package com.commerce.api.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 레이트 리미터 어댑터 <b>와이어링</b> 검증 — {@code app.ratelimit.{enabled,provider}} 토글이 어떤
 * {@link RateLimiter} 구현을 선택하는지({@code @ConditionalOnExpression})를 확인한다.
 *
 * <p>실제 앱 부팅(MySQL/Redis) 없이 {@link ApplicationContextRunner}로 조건만 격리 검증한다 — 다른 테스트는
 * 어댑터를 직접 {@code new} 하므로 Spring 조건식을 안 거친다. 이 테스트가 "조건식 오타로 빈이 0개거나 2개"
 * (부팅 시 NoUniqueBeanDefinition/NoSuchBean)를 막는다. Redis/Redisson 의존성은 목으로 채운다(조건만 관심).
 */
class RateLimiterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withUserConfiguration(
                    InMemoryRateLimiter.class,
                    RedisSlidingWindowRateLimiter.class,
                    RedissonRateLimiter.class,
                    NoOpRateLimiter.class);

    @Test
    @DisplayName("기본(provider 미설정) → InMemory(고정 윈도우)")
    void defaultsToInMemory() {
        runner.withPropertyValues("app.ratelimit.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(RateLimiter.class)
                        .getBean(RateLimiter.class).isInstanceOf(InMemoryRateLimiter.class));
    }

    @Test
    @DisplayName("provider=redis → 슬라이딩 윈도우")
    void redisSelectsSlidingWindow() {
        runner.withPropertyValues("app.ratelimit.enabled=true", "app.ratelimit.provider=redis")
                .run(ctx -> assertThat(ctx).hasSingleBean(RateLimiter.class)
                        .getBean(RateLimiter.class).isInstanceOf(RedisSlidingWindowRateLimiter.class));
    }

    @Test
    @DisplayName("provider=redisson → 토큰 버킷")
    void redissonSelectsTokenBucket() {
        runner.withPropertyValues("app.ratelimit.enabled=true", "app.ratelimit.provider=redisson")
                .run(ctx -> assertThat(ctx).hasSingleBean(RateLimiter.class)
                        .getBean(RateLimiter.class).isInstanceOf(RedissonRateLimiter.class));
    }

    @Test
    @DisplayName("enabled=false → NoOp(provider 값과 무관)")
    void disabledSelectsNoOp() {
        runner.withPropertyValues("app.ratelimit.enabled=false", "app.ratelimit.provider=redis")
                .run(ctx -> assertThat(ctx).hasSingleBean(RateLimiter.class)
                        .getBean(RateLimiter.class).isInstanceOf(NoOpRateLimiter.class));
    }
}
