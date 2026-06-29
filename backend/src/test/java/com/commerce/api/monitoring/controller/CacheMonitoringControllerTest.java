package com.commerce.api.monitoring.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.monitoring.dto.CacheStatsResponse;
import com.commerce.api.monitoring.service.CacheMonitoringService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CacheMonitoringController 슬라이스 테스트 (@WebMvcTest, 보안 필터 비활성).
 */
@WebMvcTest(CacheMonitoringController.class)
@AutoConfigureMockMvc(addFilters = false)
class CacheMonitoringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CacheMonitoringService cacheMonitoringService;

    @Test
    @DisplayName("GET /api/monitoring/caches - 캐시별 적중 통계 200")
    void getCaches() throws Exception {
        given(cacheMonitoringService.getCacheStats()).willReturn(
                List.of(new CacheStatsResponse("productDetail", 10, 9, 1, 0.9, 0, 5)));

        mockMvc.perform(get("/api/monitoring/caches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cacheName").value("productDetail"))
                .andExpect(jsonPath("$.data[0].hitRate").value(0.9))
                .andExpect(jsonPath("$.data[0].hitCount").value(9));
    }

    @Test
    @DisplayName("POST /api/monitoring/caches/{name}/evict - 200, 서비스 evict 호출")
    void evict() throws Exception {
        mockMvc.perform(post("/api/monitoring/caches/productDetail/evict"))
                .andExpect(status().isOk());
        verify(cacheMonitoringService).evict("productDetail");
    }
}
