package com.commerce.api.monitoring.dto;

/**
 * 캐시 한 개의 적중 통계(Caffeine {@code recordStats()} 기반).
 *
 * <p>{@code hitRate} = hitCount / requestCount (요청이 0이면 1.0). 통계는 Caffeine 내부에서 <b>누적</b>이라
 * 프로세스 시작 이후 합계다(캐시 clear가 통계를 리셋하지 않는다).
 */
public record CacheStatsResponse(
        String cacheName,
        long requestCount,    // hit + miss
        long hitCount,
        long missCount,
        double hitRate,       // 0.0 ~ 1.0
        long evictionCount,   // 크기/만료로 축출된 수
        long estimatedSize    // 현재 보관 중인 엔트리 추정치
) {
}
