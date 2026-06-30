package com.commerce.api.global.ratelimit;

import com.commerce.api.global.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Redis 슬라이딩 윈도우(로그 방식) 레이트 리미터 — DIY Lua. {@code app.ratelimit.provider=redis} 일 때 활성.
 *
 * <p><b>왜 슬라이딩 윈도우인가:</b> 고정 윈도우({@link InMemoryRateLimiter})는 분 경계(예: 00:00:59와
 * 00:01:00)에 요청을 몰면 두 칸에 각각 한도까지 통과해 ~2배가 짧게 샌다(경계 버스트). 슬라이딩 윈도우는
 * 요청마다 "지금부터 정확히 과거 1분"을 다시 계산하므로 그 누수가 없다. 또 카운터가 Redis에 있어
 * <b>모든 인스턴스가 공유</b>(인메모리는 인스턴스마다 따로 = 한도 누수).
 *
 * <p><b>자료구조 = 정렬셋(ZSET) 로그:</b> 키별로 요청 하나하나를 {@code {score=발생시각ms, member=유일값}}로
 * 저장한다. 코딩테스트의 "슬라이딩 윈도우(투 포인터)"와 같은 원리 — 윈도우 왼쪽 끝({@code now-1분})보다
 * 오래된 원소를 버리고 남은 개수를 센다. 다만 배열이 아니라 시간축 위의 정렬셋이고, 인메모리가 아니라 분산.
 *
 * <p><b>원자성(Lua):</b> "오래된 것 제거 → 개수 세기 → 한도 미만이면 추가"를 한 번에 실행해야, 두 요청이
 * 같은 빈자리를 동시에 차지하는 경합(읽고-나서-쓰기 사이 끼어듦)을 막는다 — 분산락 해제를 Lua로 한 것과 같은 이유.
 *
 * <p><b>대안/한계</b>: ①로그 방식은 요청마다 원소를 쌓아 메모리를 더 쓴다(키당 최대 limit개·1분 후 자동 만료).
 * 더 가벼운 근사로는 "현재+직전 칸 2개 카운터 가중합"(슬라이딩 카운터)이 있다. ②시각을 앱에서 넘기므로 다중
 * 인스턴스 시계 편차에 살짝 의존 — 정밀하게 하려면 Lua 안에서 {@code redis.call('TIME')}으로 단일 시계를 쓴다.
 * (레이트리밋은 ms 단위 정확성이 중요치 않아 앱 시각으로 충분.)
 */
@Component
@ConditionalOnExpression("${app.ratelimit.enabled:true} and '${app.ratelimit.provider:memory}'.equals('redis')")
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "ratelimit:";
    private static final long WINDOW_MS = Duration.ofMinutes(1).toMillis();

    /**
     * 슬라이딩 윈도우 로그 — 원자 실행. 반환 1=허용·0=거부.
     * KEYS[1]=키 / ARGV: 1=now(ms) 2=window(ms) 3=limit 4=member(유일값).
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local key    = KEYS[1]
            local now    = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit  = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)   -- 윈도우 밖(오래된) 기록 제거 = 왼쪽 포인터 전진
            local count = redis.call('ZCARD', key)                 -- 현재 윈도우 안 요청 수
            if count < limit then
                redis.call('ZADD', key, now, member)               -- 이번 요청 기록
                redis.call('PEXPIRE', key, window)                 -- 1분간 무요청이면 키 자동 소멸(메모리 정리)
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final long windowMs;
    private final LongSupplier clock;   // 시각 공급원 — 운영은 실제 시계, 테스트는 가짜 시계 주입

    @Autowired   // 생성자 3개(테스트용 2개 포함) 중 Spring이 주입에 쓸 것을 명시 — 없으면 부팅 시 모호.
    public RedisSlidingWindowRateLimiter(StringRedisTemplate redis) {
        this(redis, WINDOW_MS, System::currentTimeMillis);   // 운영: 1분 윈도우·실제 시계
    }

    /** 테스트용 — 윈도우를 짧게 줘(예: 1초) 슬라이딩 동작을 60초 안 기다리고 검증한다. */
    RedisSlidingWindowRateLimiter(StringRedisTemplate redis, long windowMs) {
        this(redis, windowMs, System::currentTimeMillis);
    }

    /** 테스트용 — 시계까지 주입해 윈도우 경계를 결정적으로 재현한다(고정 윈도우와의 경계 버스트 대조). */
    RedisSlidingWindowRateLimiter(StringRedisTemplate redis, long windowMs, LongSupplier clock) {
        this.redis = redis;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    @Override
    public void check(String key, int limitPerMinute) {
        long now = clock.getAsLong();
        String member = now + "-" + UUID.randomUUID();   // 같은 ms에 동시 요청이 와도 구별되도록 유일값
        Long allowed = redis.execute(SLIDING_WINDOW_SCRIPT, List.of(KEY_PREFIX + key),
                String.valueOf(now), String.valueOf(windowMs), String.valueOf(limitPerMinute), member);
        if (allowed == null || allowed == 0L) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
