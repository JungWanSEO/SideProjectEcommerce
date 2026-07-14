package com.commerce.api.global.ratelimit;

import com.commerce.api.global.exception.BusinessException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 레이트 리밋 초과 → 429.
 *
 * <p>일반 {@link BusinessException}과 달리 <b>{@code Retry-After}(초)</b>를 함께 들고 다닌다 — 429만 던지면
 * 클라이언트는 "언제 다시 오라는 건지" 알 수 없어 즉시 재시도(=더 큰 부하)하거나 과하게 오래 기다린다.
 * GlobalExceptionHandler가 이 값을 응답 헤더로 내보낸다.
 *
 * <p>윈도우가 1분(고정/슬라이딩)이라 최악의 경우 그만큼 기다리면 반드시 통과한다 → 기본 60초.
 */
@Getter
public class RateLimitExceededException extends BusinessException {

    /** 이 윈도우가 1분이므로, 60초 뒤엔 어떤 경우에도 재시도 가능. */
    public static final int DEFAULT_RETRY_AFTER_SECONDS = 60;

    private final int retryAfterSeconds;

    public RateLimitExceededException() {
        this(DEFAULT_RETRY_AFTER_SECONDS);
    }

    public RateLimitExceededException(int retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
