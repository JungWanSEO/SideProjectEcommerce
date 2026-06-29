package com.commerce.api.global.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * DIY Redis 분산락(SETNX+Lua) 검증 — 로컬 Redis(6379)가 있으면 실행, 없으면(CI 등) 건너뛴다.
 *
 * <p>Spring 컨텍스트 없이 Lettuce로 직접 붙어 락 원형만 본다: (1) 결과 반환·해제, (2) 같은 키 상호배제.
 * (실DB/브로커처럼 Redis 통합은 Testcontainers로 무조건화 — 후속.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisDistributedLockTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisDistributedLock lock;

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
        assumeTrue(reachable, "로컬 Redis(6379)가 없어 분산락 테스트를 건너뜁니다.");
        this.connectionFactory = cf;
        this.redis = new StringRedisTemplate(cf);
        this.redis.afterPropertiesSet();
        this.lock = new RedisDistributedLock(redis, 50, 3000, 5000);   // 기본값(spin 50ms·wait 3s·lease 5s)
    }

    @AfterAll
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("executeWithLock - 액션 결과를 반환하고 끝나면 락 키를 해제한다")
    void returnsValueAndReleases() {
        String key = "test:lock:" + System.nanoTime();
        String result = lock.executeWithLock(key, () -> "ok");
        assertThat(result).isEqualTo("ok");
        assertThat(redis.hasKey("lock:" + key)).isFalse();   // Lua 안전 해제로 키 제거됨
    }

    @Test
    @DisplayName("executeWithLock - 같은 키 동시 진입을 직렬화한다(상호배제)")
    void mutualExclusion() throws InterruptedException {
        String key = "test:lock:" + System.nanoTime();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    lock.executeWithLock(key, Duration.ofSeconds(5), Duration.ofSeconds(5), () -> {
                        int c = concurrent.incrementAndGet();
                        maxConcurrent.accumulateAndGet(c, Math::max);
                        try {
                            Thread.sleep(100);   // 임계구역 점유
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        concurrent.decrementAndGet();
                        return null;
                    });
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(maxConcurrent.get()).isEqualTo(1);   // 동시에 둘 이상 임계구역에 들어가지 못함
    }
}
