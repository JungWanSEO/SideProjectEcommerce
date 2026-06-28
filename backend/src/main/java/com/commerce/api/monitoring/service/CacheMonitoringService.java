package com.commerce.api.monitoring.service;

import com.commerce.api.monitoring.dto.CacheStatsResponse;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

/**
 * 캐시 적중률 모니터링 — CacheManager의 각 Caffeine 캐시에서 통계를 읽어 노출한다.
 *
 * <p>Caffeine 캐시(`recordStats()` 켜짐)만 통계가 있다. 캐시가 꺼진 환경(NoOpCacheManager)이면
 * 등록된 캐시가 없어 빈 목록을 돌려준다 — 호출부는 그대로 안전하다.
 */
@Service
@RequiredArgsConstructor
public class CacheMonitoringService {

    private final CacheManager cacheManager;

    /** 모든 Caffeine 캐시의 적중 통계(이름순). */
    public List<CacheStatsResponse> getCacheStats() {
        List<CacheStatsResponse> result = new ArrayList<>();
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache instanceof CaffeineCache caffeineCache) {
                com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                        caffeineCache.getNativeCache();
                CacheStats stats = nativeCache.stats();
                result.add(new CacheStatsResponse(
                        name, stats.requestCount(), stats.hitCount(), stats.missCount(),
                        stats.hitRate(), stats.evictionCount(), nativeCache.estimatedSize()));
            }
        }
        result.sort(Comparator.comparing(CacheStatsResponse::cacheName));
        return result;
    }
}
