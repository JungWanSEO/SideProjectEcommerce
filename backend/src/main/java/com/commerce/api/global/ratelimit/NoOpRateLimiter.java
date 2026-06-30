package com.commerce.api.global.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 레이트 리밋 비활성 — 통과만 한다. {@code app.ratelimit.enabled=false}(테스트 기본) 일 때 활성.
 *
 * <p>공유 카운터가 여러 테스트의 로그인/claim에 누적돼 교차 간섭하는 것을 막는다(캐시 {@code NoOpCacheManager}와
 * 같은 패턴). {@code enabled=false}면 provider 값과 무관하게 이 어댑터가 선택돼 Redis 연결도 시도하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.ratelimit.enabled", havingValue = "false")
public class NoOpRateLimiter implements RateLimiter {

    @Override
    public void check(String key, int limitPerMinute) {
        // no-op
    }
}
