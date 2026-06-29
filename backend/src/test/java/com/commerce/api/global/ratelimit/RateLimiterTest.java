package com.commerce.api.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 레이트 리미터 단위 테스트 — Spring 없이 직접 인스턴스화(토글 on/off·한도·키 독립성). */
class RateLimiterTest {

    @Test
    @DisplayName("한도까지는 통과, 초과하면 429")
    void allowsUpToLimitThenRejects() {
        RateLimiter limiter = new RateLimiter(true);
        for (int i = 0; i < 5; i++) {
            limiter.check("k", 5);   // 5회까지 OK
        }
        assertThatThrownBy(() -> limiter.check("k", 5))   // 6회째 거부
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("비활성(enabled=false)이면 한도 무시(no-op)")
    void disabledIsNoOp() {
        RateLimiter limiter = new RateLimiter(false);
        for (int i = 0; i < 100; i++) {
            limiter.check("k", 5);   // 절대 안 던짐
        }
    }

    @Test
    @DisplayName("키가 다르면 카운터가 독립적")
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(true);
        limiter.check("a", 1);
        limiter.check("b", 1);   // 다른 키 — OK
        assertThatThrownBy(() -> limiter.check("a", 1))   // a는 한도 초과
                .isInstanceOf(BusinessException.class);
    }
}
