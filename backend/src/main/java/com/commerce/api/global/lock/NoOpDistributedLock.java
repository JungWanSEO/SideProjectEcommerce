package com.commerce.api.global.lock;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 분산락 비활성(기본) — 락 없이 바로 실행한다. 단일 인스턴스/로컬/테스트용.
 *
 * <p>이 경로에서도 선착순 정합성은 DB 원자적 조건부 UPDATE가 보장하므로 안전하다(락은 advisory).
 * {@code app.lock.provider}가 없거나 {@code none}이면 활성, {@code redis}면 {@link RedisDistributedLock}로 교체.
 */
@Component
@ConditionalOnProperty(name = "app.lock.provider", havingValue = "none", matchIfMissing = true)
public class NoOpDistributedLock implements DistributedLock {

    @Override
    public <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        return action.get();
    }
}
