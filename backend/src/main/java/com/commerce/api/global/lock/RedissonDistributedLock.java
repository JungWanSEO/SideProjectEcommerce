package com.commerce.api.global.lock;

import com.commerce.api.global.exception.BusinessException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Redisson 기반 분산락 — {@code app.lock.provider=redisson} 일 때만 활성. DIY({@link RedisDistributedLock})와 대조.
 *
 * <p><b>watchdog(핵심 차이):</b> {@code leaseTime} 없이 {@code tryLock(wait)}으로 잡으면 Redisson이 보유하는 동안
 * <b>임대를 자동 연장</b>한다(기본 30s 임대를 10s마다 갱신). 그래서 임계구역이 길어도 락이 만료돼 풀리지 않는다 —
 * DIY 락은 lease를 미리 추측해야 하고 작업이 그보다 길면 만료되는데, 그 추측·만료 문제를 watchdog이 없앤다.
 *
 * <p>따라서 포트의 {@code leaseTime} 인자는 <b>의도적으로 무시</b>한다(임대 관리를 watchdog에 위임). 해제 안전성
 * (내 락만 해제)도 Redisson이 보장한다 — {@code isHeldByCurrentThread()} 확인 후 unlock.
 */
@Component
@ConditionalOnProperty(name = "app.lock.provider", havingValue = "redisson")
public class RedissonDistributedLock implements DistributedLock {

    private final RedissonClient redisson;

    public RedissonDistributedLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        RLock lock = redisson.getLock("lock:" + key);
        boolean acquired;
        try {
            // leaseTime 미지정 → watchdog 활성(보유 중 자동 연장). leaseTime 인자는 의도적으로 사용하지 않는다.
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "락 대기 중 중단되었습니다.");
        }
        if (!acquired) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
