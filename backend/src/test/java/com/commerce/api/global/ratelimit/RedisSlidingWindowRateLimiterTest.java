package com.commerce.api.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.commerce.api.global.exception.BusinessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * DIY Redis 슬라이딩 윈도우 레이트 리미터 검증 — 로컬 Redis(6379)가 있으면 실행, 없으면(CI 등) 건너뛴다.
 *
 * <p>윈도우를 1초로 줄여 (1) 한도 초과 시 거부, (2) 윈도우가 지나면 기록이 미끄러져 다시 허용되는지를
 * 60초 안 기다리고 확인한다. (Redis 통합 무조건화는 Testcontainers — 후속.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSlidingWindowRateLimiterTest {

    private static final long WINDOW_MS = 1000;   // 테스트용 1초 윈도우

    private LettuceConnectionFactory connectionFactory;
    private RedisSlidingWindowRateLimiter limiter;

    @BeforeAll
    void setUp() {
        LettuceConnectionFactory cf =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        cf.afterPropertiesSet();
        boolean reachable;
        try {
            cf.getConnection().ping();
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "로컬 Redis(6379)가 없어 슬라이딩 윈도우 레이트리밋 테스트를 건너뜁니다.");
        this.connectionFactory = cf;
        StringRedisTemplate redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();
        this.limiter = new RedisSlidingWindowRateLimiter(redis, WINDOW_MS);
    }

    @AfterAll
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("한도까지는 통과, 초과하면 429")
    void allowsUpToLimitThenRejects() {
        String key = "test:rl:" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            limiter.check(key, 3);   // 3회까지 OK
        }
        assertThatThrownBy(() -> limiter.check(key, 3))   // 4회째 거부
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("윈도우가 지나면 기록이 미끄러져 다시 허용(슬라이딩)")
    void windowSlides() throws InterruptedException {
        String key = "test:rl:" + System.nanoTime();
        limiter.check(key, 2);
        limiter.check(key, 2);                              // 한도 2 채움
        assertThatThrownBy(() -> limiter.check(key, 2))    // 3회째 거부
                .isInstanceOf(BusinessException.class);

        Thread.sleep(WINDOW_MS + 200);                     // 윈도우(1초) 경과 → 이전 기록은 윈도우 밖

        assertThatCode(() -> limiter.check(key, 2))        // 다시 허용 = 윈도우가 미끄러짐
                .doesNotThrowAnyException();
    }
}
