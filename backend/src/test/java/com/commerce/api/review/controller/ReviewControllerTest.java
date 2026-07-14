package com.commerce.api.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.review.dto.ReviewResponse;
import com.commerce.api.review.dto.ReviewSearchCondition;
import com.commerce.api.review.dto.ReviewSummaryResponse;
import com.commerce.api.review.dto.ReviewSummaryResponse.RatingCount;
import com.commerce.api.review.service.ReviewService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ReviewController 통합 테스트 (@WebMvcTest + MockMvc).
 * 보안 필터는 비활성하고, 현재 로그인 회원(principal=memberId)을 SecurityContext에 직접 주입한다.
 */
@WebMvcTest(ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewService reviewService;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /api/products/{id}/reviews - 작성 성공 시 201 (현재 회원·상품으로 위임)")
    void create_success() throws Exception {
        given(reviewService.create(eq(1L), eq(7L), any())).willReturn(
                new ReviewResponse(100L, 1L, "앨리스", 7L, 5, "핏이 좋아요",
                        "/products/tee.svg", LocalDateTime.now()));

        mockMvc.perform(post("/api/products/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":5,"content":"핏이 좋아요","imageUrl":"/products/tee.svg"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.writerName").value("앨리스"))
                .andExpect(jsonPath("$.data.imageUrl").value("/products/tee.svg"));
    }

    @Test
    @DisplayName("POST /api/products/{id}/reviews - 평점 범위 밖(6)·내용 누락이면 400")
    void create_validationFail() throws Exception {
        mockMvc.perform(post("/api/products/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":6,"content":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/products/{id}/reviews - 목록 200, 페이지 메타 포함 + rating·photoOnly 필터가 바인딩된다")
    void list_success() throws Exception {
        PageResponse<ReviewResponse> page = new PageResponse<>(
                List.of(new ReviewResponse(100L, 1L, "앨리스", 7L, 5, "핏이 좋아요", null, LocalDateTime.now())),
                0, 10, 1L, 1, false);
        given(reviewService.getReviews(eq(7L), any(ReviewSearchCondition.class), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/products/7/reviews").param("rating", "5").param("photoOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].rating").value(5))
                .andExpect(jsonPath("$.data.content[0].writerName").value("앨리스"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        ArgumentCaptor<ReviewSearchCondition> captor = ArgumentCaptor.forClass(ReviewSearchCondition.class);
        verify(reviewService).getReviews(eq(7L), captor.capture(), any(Pageable.class));
        assertThat(captor.getValue().rating()).isEqualTo(5);
        assertThat(captor.getValue().photoOnly()).isTrue();
    }

    @Test
    @DisplayName("GET /api/products/{id}/reviews/summary - 평점 분포 200(5★→1★, 평균)")
    void summary_success() throws Exception {
        given(reviewService.getSummary(7L)).willReturn(new ReviewSummaryResponse(3, 4.0, List.of(
                new RatingCount(5, 2L), new RatingCount(4, 0L), new RatingCount(3, 0L),
                new RatingCount(2, 1L), new RatingCount(1, 0L))));

        mockMvc.perform(get("/api/products/7/reviews/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.average").value(4.0))
                .andExpect(jsonPath("$.data.distribution[0].rating").value(5))
                .andExpect(jsonPath("$.data.distribution[0].count").value(2));
    }

    @Test
    @DisplayName("PUT /api/reviews/{id} - 수정 성공 시 200 (현재 회원으로 위임)")
    void update_success() throws Exception {
        given(reviewService.update(eq(100L), eq(1L), any())).willReturn(
                new ReviewResponse(100L, 1L, "앨리스", 7L, 4, "수정된 내용", null, LocalDateTime.now()));

        mockMvc.perform(put("/api/reviews/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":4,"content":"수정된 내용"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.content").value("수정된 내용"));
    }

    @Test
    @DisplayName("DELETE /api/reviews/{id} - 삭제 성공 시 200 (현재 회원·비ADMIN으로 위임)")
    void delete_success() throws Exception {
        mockMvc.perform(delete("/api/reviews/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(reviewService).delete(100L, 1L, false);   // principal=1L, ROLE_USER → admin=false
    }
}
