package com.commerce.api.monitoring.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.monitoring.dto.CacheStatsResponse;
import com.commerce.api.monitoring.service.CacheMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 모니터링 API — 캐시 적중률(ADMIN 전용, SecurityConfig {@code /api/monitoring/**}).
 * - GET /api/monitoring/caches   캐시별 hit/miss/적중률/축출/크기
 * (Actuator의 cache.* 메트릭과 동일 정보를 한눈에 보기 좋은 형태로 — 데모/대시보드 연동용.)
 */
@Tag(name = "모니터링(Monitoring)", description = "캐시 적중률 등 운영 모니터링 API")
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class CacheMonitoringController {

    private final CacheMonitoringService cacheMonitoringService;

    @Operation(summary = "캐시 적중률", description = "각 캐시의 요청수·적중·미스·적중률·축출수·현재크기. recordStats 누적(프로세스 시작 이후 합계).")
    @GetMapping("/caches")
    public ResponseEntity<ApiResponse<List<CacheStatsResponse>>> getCacheStats() {
        return ResponseEntity.ok(ApiResponse.success(cacheMonitoringService.getCacheStats()));
    }

    @Operation(summary = "캐시 비우기", description = "지정 캐시를 비운다(운영 — 데이터 보정 후 stale 제거, 재기동 없이). 없는 캐시면 404.")
    @PostMapping("/caches/{name}/evict")
    public ResponseEntity<ApiResponse<Void>> evict(@PathVariable String name) {
        cacheMonitoringService.evict(name);
        return ResponseEntity.ok(ApiResponse.success("캐시를 비웠습니다.", null));
    }
}
