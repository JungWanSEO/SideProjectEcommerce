package com.commerce.api.global.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 메서드는 <b>레이트 리밋을 건다</b>는 선언. {@link RateLimitAspect}가 잡아 {@link RateLimiter}를 호출한다
 * (초과 시 429 + Retry-After). {@code @Auditable}과 같은 결의 횡단 관심사 분리 — 업무 코드는 제한을 몰라도 된다.
 *
 * <p>제한 키는 <b>{@code key + ":" + 식별자}</b>로 만든다. 식별자는 {@link #by()} SpEL의 평가값이고,
 * 비우면 <b>클라이언트 IP</b>다(비로그인 공개 API용).
 *
 * <pre>
 * &#64;RateLimit(key = "login", limit = 5, by = "#request.email()")   // 이메일당 1분 5회
 * &#64;RateLimit(key = "claim", limit = 20, by = "#memberId")          // 회원당 1분 20회
 * &#64;RateLimit(key = "feed", limit = 60)                             // IP당 1분 60회(스크래핑 억제)
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 제한 대상 이름(키 접두사). 예: "login"·"feed"·"claim". */
    String key();

    /** 1분당 허용 횟수. */
    int limit();

    /**
     * 누구를 기준으로 셀지 뽑는 SpEL(메서드 인자 참조). 예: {@code "#memberId"}, {@code "#request.email()"}.
     * 비우면 클라이언트 IP를 쓴다. 평가값이 null이면 {@code "unknown"}으로 센다(키가 통째로 사라지지 않게).
     */
    String by() default "";
}
