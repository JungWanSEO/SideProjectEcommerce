package com.commerce.api.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link RateLimitAspect} 단위 테스트 — AspectJProxyFactory로 @RateLimit 타깃을 프록시해 아스펙트만 검증한다
 * ({@code AuditAspectTest}와 같은 방식). 키 조립(SpEL·IP)·한도 전달·429 전파를 확인.
 */
class RateLimitAspectTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);

    private Sample proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Sample());
        factory.addAspect(new RateLimitAspect(rateLimiter));
        return factory.getProxy();
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("by(SpEL)로 인자에서 식별자를 뽑아 '키:식별자'로 센다 (반환값은 그대로 통과)")
    void buildsKeyFromSpel() {
        String result = proxied().claim(42L);

        assertThat(result).isEqualTo("claimed:42");   // 아스펙트가 반환값을 바꾸지 않는다
        verify(rateLimiter).check(eq("claim:42"), eq(20));
    }

    @Test
    @DisplayName("by가 없으면 클라이언트 IP로 센다(공개 API — 비로그인이라 회원 기준이 불가)")
    void fallsBackToClientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        proxied().feed();

        verify(rateLimiter).check(eq("feed:203.0.113.9"), eq(60));
    }

    @Test
    @DisplayName("요청 컨텍스트가 없으면(스케줄러 등) unknown으로 센다 — 키가 사라져 제한이 무력화되지 않게")
    void unknownWithoutRequestContext() {
        proxied().feed();

        verify(rateLimiter).check(eq("feed:unknown"), eq(60));
    }

    @Test
    @DisplayName("한도 초과(429)는 그대로 전파되고 대상 메서드는 실행되지 않는다")
    void propagatesTooManyRequests() {
        willThrow(new RateLimitExceededException()).given(rateLimiter).check(eq("claim:42"), eq(20));

        assertThatThrownBy(() -> proxied().claim(42L))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting("status").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** 아스펙트를 적용할 타깃(테스트 전용). */
    static class Sample {

        @RateLimit(key = "claim", limit = 20, by = "#memberId")
        String claim(Long memberId) {
            return "claimed:" + memberId;
        }

        @RateLimit(key = "feed", limit = 60)
        String feed() {
            return "feed";
        }
    }
}
