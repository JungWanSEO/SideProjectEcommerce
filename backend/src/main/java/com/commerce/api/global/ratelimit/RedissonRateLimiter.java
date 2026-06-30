package com.commerce.api.global.ratelimit;

import com.commerce.api.global.exception.BusinessException;
import java.time.Duration;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Redisson 토큰 버킷 레이트 리미터 — 라이브러리 위임. {@code app.ratelimit.provider=redisson} 일 때 활성.
 * DIY 슬라이딩 윈도우({@link RedisSlidingWindowRateLimiter})와 <b>대조</b>한다(분산락의 DIY vs Redisson과 같은 구도).
 *
 * <p><b>토큰 버킷(슬라이딩과 다른 점):</b> "최근 1분에 정확히 N건"을 세는 슬라이딩과 달리, 버킷에 토큰을
 * 일정 속도로 채우고(여기선 1분에 limit개) 요청마다 1개 소비한다. 토큰이 없으면 거부. 결과적으로 평균은
 * limit/분으로 수렴하되 <b>모아둔 토큰으로 짧은 버스트를 허용</b>한다 — API 레이트리밋의 사실상 표준이고
 * .NET {@code TokenBucketRateLimiter}와 동형.
 *
 * <p><b>왜 라이브러리:</b> 우리가 Lua로 짠 슬라이딩과 비교하기 위함. Redisson {@link RRateLimiter}는 분산
 * 토큰 버킷을 직접 제공한다(카운팅 원자성·키 만료를 라이브러리가 보장) → DIY의 Lua/경합 처리를 대신해 준다.
 */
@Component
@ConditionalOnExpression("${app.ratelimit.enabled:true} and '${app.ratelimit.provider:memory}'.equals('redisson')")
public class RedissonRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "ratelimit:rl:";

    private final RedissonClient redisson;

    public RedissonRateLimiter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public void check(String key, int limitPerMinute) {
        RRateLimiter limiter = redisson.getRateLimiter(KEY_PREFIX + key);
        // 분당 limit개 — 키별 최초 1회만 실제 설정(이미 있으면 값 유지). OVERALL=모든 클라이언트 합산(분산 공유).
        limiter.trySetRate(RateType.OVERALL, limitPerMinute, Duration.ofMinutes(1));
        if (!limiter.tryAcquire()) {   // 토큰 1개 시도 — 없으면 거부
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
