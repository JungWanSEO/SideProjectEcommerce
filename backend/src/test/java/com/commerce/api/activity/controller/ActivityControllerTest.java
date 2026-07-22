package com.commerce.api.activity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.activity.service.ActivityLogService;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.ProductStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ActivityController 슬라이스 테스트 (@WebMvcTest) — 조회 기록 + 최근 본 상품.
 * SecurityContext에 현재 회원(principal=9)을 심어, 컨트롤러가 그 memberId로 위임하는지 확인한다.
 */
@WebMvcTest(ActivityController.class)
@AutoConfigureMockMvc(addFilters = false)
class ActivityControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ActivityLogService activityLogService;

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
    @DisplayName("POST /api/activity/views - 201 + 현재 회원·상품으로 기록 위임")
    void logView_created() throws Exception {
        mockMvc.perform(post("/api/activity/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":42}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        verify(activityLogService).logView(9L, 42L);   // principal=9
    }

    @Test
    @DisplayName("POST /api/activity/views - productId 누락 시 400")
    void logView_validationFail() throws Exception {
        mockMvc.perform(post("/api/activity/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/activity/recently-viewed - 200 + limit·exclude 바인딩(기본 limit=8)")
    void recentlyViewed_bindsParams() throws Exception {
        given(activityLogService.getRecentlyViewed(eq(9L), eq(5), eq(3L)))
                .willReturn(List.of(product(1L), product(2L)));

        mockMvc.perform(get("/api/activity/recently-viewed").param("limit", "5").param("exclude", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[1].id").value(2));

        verify(activityLogService).getRecentlyViewed(9L, 5, 3L);
    }

    @Test
    @DisplayName("GET /api/activity/recently-viewed - 파라미터 없으면 기본 limit=8·exclude=null")
    void recentlyViewed_defaults() throws Exception {
        given(activityLogService.getRecentlyViewed(eq(9L), eq(8), isNull())).willReturn(List.of());

        mockMvc.perform(get("/api/activity/recently-viewed"))
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(activityLogService).getRecentlyViewed(eq(9L), limit.capture(), isNull());
        assertThat(limit.getValue()).isEqualTo(8);
    }
}
