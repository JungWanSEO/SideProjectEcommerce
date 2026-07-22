package com.commerce.api.recommendation.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.recommendation.dto.CoOccurrenceResponse;
import com.commerce.api.recommendation.dto.RecommendationResponse;
import com.commerce.api.recommendation.service.CoOccurrenceBatchService;
import com.commerce.api.recommendation.service.CoOccurrenceService;
import com.commerce.api.recommendation.service.RecommendationBatchService;
import com.commerce.api.recommendation.service.RecommendationService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RecommendationController 슬라이스 테스트 (@WebMvcTest) — 나를 위한 추천 / 함께 산 상품 / 배치 재계산.
 */
@WebMvcTest(RecommendationController.class)
@AutoConfigureMockMvc(addFilters = false)
class RecommendationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RecommendationService recommendationService;
    @MockitoBean private RecommendationBatchService recommendationBatchService;
    @MockitoBean private CoOccurrenceService coOccurrenceService;
    @MockitoBean private CoOccurrenceBatchService coOccurrenceBatchService;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        9L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private ProductResponse product(Long id) {
        return new ProductResponse(id, "상품" + id, 10000L, "설명", null, ProductStatus.ON_SALE,
                null, null, null, null, List.of(), 0, 0.0, 0, LocalDateTime.now());
    }

    @Test
    @DisplayName("GET /api/recommendations/me - 내 추천 200(현재 회원으로 조회)")
    void myRecommendations_success() throws Exception {
        given(recommendationService.getMyRecommendations(9L))
                .willReturn(new RecommendationResponse(true, List.of(product(1L))));

        mockMvc.perform(get("/api/recommendations/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalized").value(true))
                .andExpect(jsonPath("$.data.products[0].id").value(1));

        verify(recommendationService).getMyRecommendations(9L);
    }

    @Test
    @DisplayName("GET /api/recommendations/products/{id}/together - 함께 산 상품 200(limit 기본 8)")
    void together_success() throws Exception {
        given(coOccurrenceService.getCoOccurrence(eq(42L), eq(8)))
                .willReturn(new CoOccurrenceResponse(true, List.of(product(2L))));

        mockMvc.perform(get("/api/recommendations/products/42/together"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cooccurrence").value(true))
                .andExpect(jsonPath("$.data.products[0].id").value(2));
    }

    @Test
    @DisplayName("POST /api/recommendations/run - 추천 배치 재계산 200(생성 수 반환)")
    void run_success() throws Exception {
        given(recommendationBatchService.run()).willReturn(12);

        mockMvc.perform(post("/api/recommendations/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(12));
    }

    @Test
    @DisplayName("POST /api/recommendations/cooccurrence/run - 함께 산 상품 배치 200")
    void runCoOccurrence_success() throws Exception {
        given(coOccurrenceBatchService.run()).willReturn(30);

        mockMvc.perform(post("/api/recommendations/cooccurrence/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(30));
    }
}
