package com.commerce.api.global.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** NoOpDistributedLock 단위 — 락 없이 액션을 정확히 1회 실행하고 결과를 그대로 돌려준다(기본 어댑터 계약). */
class NoOpDistributedLockTest {

    private final DistributedLock lock = new NoOpDistributedLock();

    @Test
    @DisplayName("executeWithLock - 액션을 1회 실행하고 결과 반환")
    void runsActionOnceAndReturns() {
        AtomicInteger runs = new AtomicInteger();
        String result = lock.executeWithLock("k", () -> {
            runs.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("wait/lease 인자를 받는 오버로드도 그대로 실행")
    void fullSignatureAlsoRuns() {
        String result = lock.executeWithLock("k", Duration.ofSeconds(1), Duration.ofSeconds(1), () -> "v");
        assertThat(result).isEqualTo("v");
    }
}
