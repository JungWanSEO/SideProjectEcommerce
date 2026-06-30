package com.commerce.api.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.commerce.api.global.exception.BusinessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Redisson 토큰 버킷 레이트 리미터 검증 — 로컬 Redis(6379) 있으면 실행, 없으면(CI 등) skip.
 *
 * <p>슬라이딩 윈도우({@link RedisSlidingWindowRateLimiterTest})와 <b>구현은 다르나 외부 계약은 같다</b>(429).
 * 여기선 "분당 한도(=버킷 용량)를 빠르게 소진하면 거부"를 본다. (Redisson 통합 무조건화는 Testcontainers — 후속.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonRateLimiterTest {

    private RedissonClient redisson;
    private RedissonRateLimiter limiter;

    @BeforeAll
    void setUp() {
        boolean reachable;
        RedissonClient client = null;
        try {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:6379")
                    .setConnectTimeout(1000).setRetryAttempts(1);
            client = Redisson.create(config);
            client.getBucket("ping:" + System.nanoTime()).isExists();   // 실제 통신으로 가용성 확인
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "로컬 Redis(6379)가 없어 Redisson 레이트리밋 테스트를 건너뜁니다.");
        this.redisson = client;
        this.limiter = new RedissonRateLimiter(redisson);
    }

    @AfterAll
    void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("토큰 버킷: 분당 한도(버킷)까지 통과, 소진되면 429")
    void allowsUpToLimitThenRejects() {
        String key = "test:rl:redisson:" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            limiter.check(key, 3);   // 토큰 3개(=버킷 용량) 소비
        }
        assertThatThrownBy(() -> limiter.check(key, 3))   // 4회째 = 토큰 없음(1분에 3개만 리필)
                .isInstanceOf(BusinessException.class);
    }
}
