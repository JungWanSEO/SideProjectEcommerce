package com.commerce.api.global.lock;

import com.commerce.api.global.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 분산락(SETNX + Lua) — {@code app.lock.provider=redis} 일 때만 활성.
 *
 * <p><b>획득</b>: {@code SET lock:{key} {token} NX PX {lease}} (원자). token=UUID로 소유권 식별,
 * lease(PX)로 보유자가 죽어도 자동 만료 → 데드락 방지. {@code waitTime} 까지 짧은 간격으로 재시도(spin).
 *
 * <p><b>해제</b>: <b>Lua 원자 스크립트</b>로 "내 토큰일 때만 DEL". (단순 GET→DEL은 그 사이 락이 만료돼
 * 다른 클라이언트가 새로 잡은 락을 실수로 지울 수 있다 — 그래서 검사+삭제를 한 번에.)
 *
 * <p><b>한계</b>: watchdog(자동 임대 연장) 없음 — 임계구역이 lease보다 길면 만료된다. 쿠폰 claim은 ms 단위라
 * 안전하나, 장수명 락이 필요하면 Redisson watchdog을 쓴다(docs/distributed-lock-study.md).
 */
@Component
@ConditionalOnProperty(name = "app.lock.provider", havingValue = "redis")
public class RedisDistributedLock implements DistributedLock {

    private static final String KEY_PREFIX = "lock:";
    private static final long RETRY_INTERVAL_MS = 50L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        String lockKey = KEY_PREFIX + key;
        String token = UUID.randomUUID().toString();
        if (!acquire(lockKey, token, waitTime, leaseTime)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        try {
            return action.get();
        } finally {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);   // 내 토큰일 때만 해제(원자)
        }
    }

    /** {@code SET NX PX} 를 waitTime 까지 재시도. 성공 시 true, 시간 초과 시 false. */
    private boolean acquire(String lockKey, String token, Duration waitTime, Duration leaseTime) {
        long deadlineNanos = System.nanoTime() + waitTime.toNanos();
        while (true) {
            Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, leaseTime);   // NX + PX(만료)
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
