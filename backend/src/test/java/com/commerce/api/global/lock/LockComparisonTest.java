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
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * DIY 락 vs Redisson watchdog <b>비교 실습</b> — 로컬 Redis(6379) 있으면 실행, 없으면(CI 등) skip.
 *
 * <p><b>대조의 핵심(작업이 lease보다 길 때):</b>
 * <ul>
 *   <li>DIY {@link RedisDistributedLock}(lease 1s, work 2s): 1s에 락이 만료 → 대기하던 다른 스레드가 진입 →
 *       <b>상호배제 깨짐(동시=2)</b>. lease를 미리 추측해야 하고 작업이 그보다 길면 무너지는 한계.</li>
 *   <li>Redisson {@link RedissonDistributedLock}(watchdog): 보유 중 임대를 자동 연장 → 작업 끝까지 유지 →
 *       <b>상호배제 유지(동시=1)</b>. lease 추측이 필요 없다.</li>
 * </ul>
 * watchdog 효과를 빠르게 검증하려고 watchdog timeout=1s로 낮춘다(2s 작업이 유지되면 1s 임대를 연장한 증거).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockComparisonTest {

    private LettuceConnectionFactory factory;
    private RedissonClient redisson;
    private DistributedLock diyLock;
    private DistributedLock redissonLock;

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
        this.factory = cf;

        StringRedisTemplate template = new StringRedisTemplate(cf);
        template.afterPropertiesSet();
        this.diyLock = new RedisDistributedLock(template, 50, 3000, 5000);   // 기본값(spin 50ms)

        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        config.setLockWatchdogTimeout(1000);   // 1s 임대를 ~0.33s마다 갱신 → 2s 작업 유지 시 watchdog가 연장한 증거
        this.redisson = Redisson.create(config);
        this.redissonLock = new RedissonDistributedLock(redisson);
    }

    @AfterAll
    void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("DIY 락 - 작업(2s)이 lease(1s)보다 길면 만료돼 상호배제가 깨진다(동시=2)")
    void diy_breaksWhenWorkExceedsLease() throws InterruptedException {
        int max = maxConcurrent(diyLock, Duration.ofSeconds(1), 2000L, 2);
        assertThat(max).isEqualTo(2);   // DIY 한계: lease 만료 → 중복 진입
    }

    @Test
    @DisplayName("Redisson watchdog - 작업(2s)이 길어도 자동 연장으로 상호배제가 유지된다(동시=1)")
    void redisson_holdsWithWatchdog() throws InterruptedException {
        int max = maxConcurrent(redissonLock, Duration.ofSeconds(1), 2000L, 2);
        assertThat(max).isEqualTo(1);   // watchdog가 보유 중 임대 연장 → 만료 없음
    }

    /** threads 개가 같은 키로 동시에 executeWithLock 하며 workMs 동안 점유. 임계구역 동시 진입 최대치를 반환. */
    private int maxConcurrent(DistributedLock lock, Duration lease, long workMs, int threads)
            throws InterruptedException {
        String key = "test:cmp:" + System.nanoTime();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    lock.executeWithLock(key, Duration.ofSeconds(10), lease, () -> {
                        int c = concurrent.incrementAndGet();
                        max.accumulateAndGet(c, Math::max);
                        sleep(workMs);
                        concurrent.decrementAndGet();
                        return null;
                    });
                } catch (Exception ignored) {
                    // 락 실패(503 등)는 이 비교에선 무시 — 동시 진입 수만 관찰.
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        return max.get();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
