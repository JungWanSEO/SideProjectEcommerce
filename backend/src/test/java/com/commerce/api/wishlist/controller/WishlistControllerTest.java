package com.commerce.api.wishlist.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.wishlist.dto.WishlistResponse;
import com.commerce.api.wishlist.service.WishlistService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WishlistController 슬라이스 테스트 (@WebMvcTest) — 찜 추가/해제/목록/ID목록.
 */
@WebMvcTest(WishlistController.class)
@AutoConfigureMockMvc(addFilters = false)
class WishlistControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WishlistService wishlistService;

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

    private WishlistResponse wish(Long id, Long productId) {
        return new WishlistResponse(id, productId, LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("POST /api/wishlist - 찜 추가 201(현재 회원·상품으로 위임)")
    void add_created() throws Exception {
        given(wishlistService.add(9L, 42L)).willReturn(wish(1L, 42L));

        mockMvc.perform(post("/api/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":42}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productId").value(42));

        verify(wishlistService).add(9L, 42L);
    }

    @Test
    @DisplayName("POST /api/wishlist - 이미 찜한 상품이면 409")
    void add_conflict() throws Exception {
        given(wishlistService.add(9L, 42L))
                .willThrow(new BusinessException(HttpStatus.CONFLICT, "이미 찜한 상품입니다."));

        mockMvc.perform(post("/api/wishlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":42}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("DELETE /api/wishlist/{productId} - 찜 해제 200")
    void remove_success() throws Exception {
        mockMvc.perform(delete("/api/wishlist/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(wishlistService).remove(9L, 42L);
    }

    @Test
    @DisplayName("GET /api/wishlist/me - 내 찜 목록 200")
    void myWishlist_success() throws Exception {
        given(wishlistService.getMyWishlist(eq(9L), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(wish(1L, 42L)), 0, 20, 1L, 1, false));

        mockMvc.perform(get("/api/wishlist/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].productId").value(42));
    }

    @Test
    @DisplayName("GET /api/wishlist/me/product-ids - 찜한 상품 ID 목록 200")
    void myProductIds_success() throws Exception {
        given(wishlistService.getMyProductIds(9L)).willReturn(List.of(1L, 42L, 7L));

        mockMvc.perform(get("/api/wishlist/me/product-ids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(1))
                .andExpect(jsonPath("$.data[1]").value(42));
    }
}
