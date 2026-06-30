package com.commerce.api.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.commerce.api.global.exception.BusinessException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 고정 윈도우 vs 슬라이딩 윈도우 <b>비교 실습</b> — 로컬 Redis(6379) 있으면 실행, 없으면(CI 등) skip.
 *
 * <p><b>대조의 핵심(경계 버스트):</b> 한도 5/윈도우 1초에서, 한 칸 끝자락(t=999ms)에 5건 + 다음 칸 시작
 * (t=1000ms)에 5건을 몰아 보낸다(≈1ms 사이 10건).
 * <ul>
 *   <li><b>고정 윈도우</b>({@link InMemoryRateLimiter}와 같은 알고리즘 = {@code now/window} 칸별 카운터):
 *       두 건이 서로 다른 칸이라 각 칸이 한도까지 통과 → <b>10건 통과(2×한도 누수)</b>.</li>
 *   <li><b>슬라이딩 윈도우</b>({@link RedisSlidingWindowRateLimiter}, 같은 가짜 시계 주입): t=1000에서도 t=999의
 *       5건이 최근 1초 안이라 카운트 → <b>정확히 5건만 통과</b>.</li>
 * </ul>
 * 시계를 주입해 경계를 결정적으로 재현한다(벽시계 의존 제거 → 흔들리지 않는 테스트).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimiterComparisonTest {

    private static final int LIMIT = 5;
    private static final long WINDOW_MS = 1000;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

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
        assumeTrue(reachable, "로컬 Redis(6379)가 없어 비교 실습을 건너뜁니다.");
        this.connectionFactory = cf;
        this.redis = new StringRedisTemplate(cf);
        this.redis.afterPropertiesSet();
    }

    @AfterAll
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("경계 버스트: 고정 윈도우는 2×한도를 통과시키고(누수), 슬라이딩은 한도로 막는다")
    void boundaryBurst_fixedLeaks_slidingHolds() {
        AtomicLong fakeNow = new AtomicLong();
        RedisSlidingWindowRateLimiter sliding =
                new RedisSlidingWindowRateLimiter(redis, WINDOW_MS, fakeNow::get);
        String key = "test:cmp:" + System.nanoTime();

        // 고정 윈도우(InMemoryRateLimiter와 동일 알고리즘)를 같은 가짜 시계로 모사 — 칸 = now/window
        Map<Long, Integer> fixedBuckets = new HashMap<>();

        int fixedAllowed = 0;
        int slidingAllowed = 0;

        // 1차: 윈도우 끝자락(t=999ms)에 한도만큼
        fakeNow.set(WINDOW_MS - 1);
        for (int i = 0; i < LIMIT; i++) {
            if (fixedAllow(fixedBuckets, fakeNow.get())) {
                fixedAllowed++;
            }
            if (slidingAllow(sliding, key)) {
                slidingAllowed++;
            }
        }
        // 2차: 다음 칸 시작(t=1000ms)에 한도만큼 — 고정 윈도우 입장에선 새 칸이 열림
        fakeNow.set(WINDOW_MS);
        for (int i = 0; i < LIMIT; i++) {
            if (fixedAllow(fixedBuckets, fakeNow.get())) {
                fixedAllowed++;
            }
            if (slidingAllow(sliding, key)) {
                slidingAllowed++;
            }
        }

        assertThat(fixedAllowed).isEqualTo(2 * LIMIT);   // 10 — 경계에서 두 칸에 각각 통과 = 누수
        assertThat(slidingAllowed).isEqualTo(LIMIT);     // 5 — 최근 1초에 정확히 한도(경계 버스트 없음)
    }

    /** 고정 윈도우 판정 — now/window 칸 카운터가 한도 이하면 허용. (InMemoryRateLimiter의 알고리즘과 동일.) */
    private boolean fixedAllow(Map<Long, Integer> buckets, long now) {
        long bucket = now / WINDOW_MS;
        return buckets.merge(bucket, 1, Integer::sum) <= LIMIT;
    }

    /** 슬라이딩 윈도우 판정 — check()가 429를 던지면 거부. */
    private boolean slidingAllow(RateLimiter limiter, String key) {
        try {
            limiter.check(key, LIMIT);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
