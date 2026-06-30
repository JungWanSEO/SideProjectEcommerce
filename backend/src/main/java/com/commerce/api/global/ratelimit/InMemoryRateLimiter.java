package com.commerce.api.global.ratelimit;

import com.commerce.api.global.exception.BusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 인메모리 레이트 리미터(기본) — 키별 <b>1분 고정 윈도우</b> 카운터. 외부 의존 0(Caffeine 재사용).
 * {@code app.ratelimit.enabled=true && provider=memory}(기본) 일 때 활성.
 *
 * <p>윈도우=1분 고정(Caffeine {@code expireAfterWrite}): 키별 첫 요청에 카운터 생성 → 1분 뒤 만료 → 새 윈도우.
 * 증가는 {@code AtomicInteger}라 TTL이 리셋되지 않음 = 진짜 고정 윈도우.
 *
 * <p><b>고정 윈도우의 한계(경계 버스트):</b> 1분 칸 경계(예: 00:00:59와 00:01:00)에 요청을 몰면 두 칸에 각각
 * 한도까지 통과해 ~2배가 짧은 시간에 샌다. 또 인메모리라 <b>단일 인스턴스</b> 기준(다중 인스턴스는 인스턴스마다
 * 따로 카운트 = 한도 누수). 이 둘을 동시에 고치는 게 {@link RedisSlidingWindowRateLimiter}(분산 + 슬라이딩).
 */
@Component
@ConditionalOnExpression("${app.ratelimit.enabled:true} and '${app.ratelimit.provider:memory}'.equals('memory')")
public class InMemoryRateLimiter implements RateLimiter {

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();

    @Override
    public void check(String key, int limitPerMinute) {
        int count = counters.get(key, k -> new AtomicInteger()).incrementAndGet();
        if (count > limitPerMinute) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
