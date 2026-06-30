package com.commerce.api.global.ratelimit;

/**
 * 레이트 리밋 포트(port) — 키별 "1분당 N회" 제한. 구현(어댑터)을 설정으로 교체한다:
 * memory(기본·인메모리 고정 윈도우) / redis(DIY Lua 슬라이딩 윈도우) / redisson(토큰 버킷).
 *
 * <p>분산락({@code DistributedLock})·캐시({@code CacheManager})와 같은 포트-어댑터 패턴: 호출부
 * (로그인·쿠폰 claim)는 이 인터페이스만 의존하고, 단일 인스턴스(메모리)↔다중 인스턴스(Redis 공유 카운터)를
 * 설정 토글({@code app.ratelimit.provider})로 오간다. (.NET의 {@code IRateLimiter} + DI 와 동형.)
 *
 * <p><b>왜 분산이 필요한가:</b> 인메모리 카운터는 인스턴스마다 따로라, 앱을 2대로 늘리면 "5회/분"이 사실상
 * "10회/분"이 된다(한도 누수). Redis로 옮기면 모든 인스턴스가 같은 카운터를 본다 — 캐시·락의 단일↔분산 동기와 동일.
 */
public interface RateLimiter {

    /** {@code key}의 최근 1분 호출이 {@code limitPerMinute}를 넘으면 429(TOO_MANY_REQUESTS)를 던진다. */
    void check(String key, int limitPerMinute);
}
