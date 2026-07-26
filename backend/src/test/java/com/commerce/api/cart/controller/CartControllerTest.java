package com.commerce.api.cart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.cart.dto.CartResponse;
import com.commerce.api.cart.dto.CartResponse.CartItemResponse;
import com.commerce.api.cart.service.CartCookieManager;
import com.commerce.api.cart.service.CartOwner;
import com.commerce.api.cart.service.CartService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * CartController 통합 테스트 (@WebMvcTest + MockMvc).
 * 보안 필터 비활성 + 로그인 회원(principal=1L)을 SecurityContext에 주입.
 */
@WebMvcTest(CartController.class)
@AutoConfigureMockMvc(addFilters = false)
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private CartCookieManager cartCookieManager;   // #7 — @WebMvcTest 슬라이스엔 @Component가 안 실려 목으로 주입

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

    private CartResponse sampleCart() {
        // CartItemResponse(productId, optionId, productName, size, price, quantity, subtotal, stock, soldOut)
        return new CartResponse(1L,
                List.of(new CartItemResponse(1L, 10L, "반팔티셔츠", "M", 10000L, 2, 20000L, 50, false)), 2);
    }

    @Test
    @DisplayName("POST /api/carts/items - 담기 성공 시 200")
    void addItem_success() throws Exception {
        given(cartService.addItem(any(CartOwner.class), any())).willReturn(sampleCart());

        mockMvc.perform(post("/api/carts/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId":10,"quantity":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalQuantity").value(2))
                .andExpect(jsonPath("$.data.items[0].optionId").value(10))
                .andExpect(jsonPath("$.data.items[0].size").value("M"))
                .andExpect(jsonPath("$.data.items[0].subtotal").value(20000));
    }

    @Test
    @DisplayName("POST /api/carts/items - 비로그인 게스트는 담기 시 cart_token 쿠키 발급(#7)")
    void addItem_guest_issuesCookie() throws Exception {
        SecurityContextHolder.clearContext();   // 비로그인
        given(cartService.addItem(any(CartOwner.class), any())).willReturn(sampleCart());
        given(cartCookieManager.newToken()).willReturn("guest-tok-1");
        given(cartCookieManager.issue("guest-tok-1")).willReturn(
                org.springframework.http.ResponseCookie.from("cart_token", "guest-tok-1").httpOnly(true).path("/").build());

        mockMvc.perform(post("/api/carts/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId":10,"quantity":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("cart_token=guest-tok-1")));
    }

    @Test
    @DisplayName("POST /api/carts/items - 수량이 0 이하면 400")
    void addItem_validationFail() throws Exception {
        mockMvc.perform(post("/api/carts/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId":10,"quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/carts - 조회 성공 시 200")
    void getCart_success() throws Exception {
        given(cartService.getCart(any(CartOwner.class))).willReturn(sampleCart());

        mockMvc.perform(get("/api/carts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productName").value("반팔티셔츠"))
                .andExpect(jsonPath("$.data.items[0].size").value("M"));
    }

    @Test
    @DisplayName("PUT /api/carts/items/{optionId} - 수량 변경 성공 시 200")
    void updateItemQuantity_success() throws Exception {
        // 수량을 5로 변경한 장바구니 응답을 stub
        CartResponse updated = new CartResponse(1L,
                List.of(new CartItemResponse(1L, 10L, "반팔티셔츠", "M", 10000L, 5, 50000L, 50, false)), 5);
        given(cartService.changeQuantity(any(CartOwner.class), eq(10L), any())).willReturn(updated);

        mockMvc.perform(put("/api/carts/items/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].quantity").value(5))
                .andExpect(jsonPath("$.data.items[0].subtotal").value(50000))
                .andExpect(jsonPath("$.data.totalQuantity").value(5));
    }

    @Test
    @DisplayName("PUT /api/carts/items/{optionId} - 수량이 0 이하면 400")
    void updateItemQuantity_validationFail() throws Exception {
        mockMvc.perform(put("/api/carts/items/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("DELETE /api/carts/items/{optionId} - 제거 성공 시 200")
    void removeItem_success() throws Exception {
        given(cartService.removeItem(any(CartOwner.class), anyLong()))
                .willReturn(new CartResponse(1L, List.of(), 0));

        mockMvc.perform(delete("/api/carts/items/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuantity").value(0));
    }
}
