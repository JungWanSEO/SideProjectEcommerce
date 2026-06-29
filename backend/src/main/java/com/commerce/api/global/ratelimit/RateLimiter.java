package com.commerce.api.global.ratelimit;

import com.commerce.api.global.exception.BusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 간단한 인메모리 레이트 리미터 — 키별 "1분 고정 윈도우" 카운터. 외부 의존 0(이미 있는 Caffeine 재사용).
 *
 * <p>무차별 로그인·요청 스팸을 앱 계층에서 막는다. 윈도우=1분 고정(Caffeine {@code expireAfterWrite}):
 * 키별 첫 요청에 카운터 생성 → 1분 뒤 만료 → 새 윈도우(증가는 AtomicInteger라 TTL 리셋 안 됨 = 진짜 고정 윈도우).
 *
 * <p><b>한계/확장</b>: 인메모리라 단일 인스턴스 기준(다중 인스턴스는 인스턴스마다 카운트) — 분산은 Redis
 * {@code INCR + EXPIRE}로 확장. <b>테스트 격리</b>: {@code app.ratelimit.enabled=false}(테스트 기본)면
 * no-op이라 공유 카운터가 테스트 결정성을 깨지 않는다(캐시 토글과 같은 패턴).
 */
@Component
public class RateLimiter {

    private final boolean enabled;
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();

    public RateLimiter(@Value("${app.ratelimit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** {@code key}의 1분 내 호출이 {@code limitPerMinute}를 넘으면 429(TOO_MANY_REQUESTS). */
    public void check(String key, int limitPerMinute) {
        if (!enabled) {
            return;
        }
        int count = counters.get(key, k -> new AtomicInteger()).incrementAndGet();
        if (count > limitPerMinute) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
