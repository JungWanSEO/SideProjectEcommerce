package com.commerce.api.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.auth.dto.AuthResult;
import com.commerce.api.auth.service.AuthService;
import com.commerce.api.global.security.AuthCookieManager;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.service.MemberService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AuthController 통합 테스트 (@WebMvcTest + MockMvc).
 * 토큰은 httpOnly 쿠키로 주고받으므로 body엔 토큰이 없고(유저 정보만), Set-Cookie가 내려가는지 검증한다.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private MemberService memberService;
    @MockitoBean
    private AuthCookieManager cookieManager;

    private MemberResponse user() {
        return new MemberResponse(1L, "alice@commerce.com", "alice", Role.USER, null, null);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /api/auth/login - 성공 시 200 + 쿠키 set, body엔 유저정보(토큰 미포함)")
    void login_success() throws Exception {
        given(authService.login(any())).willReturn(new AuthResult("access.v", "refresh.v", user()));
        given(cookieManager.accessCookie("access.v"))
                .willReturn(ResponseCookie.from("access_token", "access.v").httpOnly(true).build());
        given(cookieManager.refreshCookie("refresh.v"))
                .willReturn(ResponseCookie.from("refresh_token", "refresh.v").httpOnly(true).build());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@commerce.com","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("alice@commerce.com"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())   // 토큰은 body에 없음
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("access_token", true));
    }

    @Test
    @DisplayName("POST /api/auth/login - 이메일 누락 시 400")
    void login_validationFail() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"","password":"password123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/auth/refresh - 쿠키의 refresh로 재발급 → 새 쿠키 set")
    void refresh_success() throws Exception {
        given(authService.refresh("old.refresh")).willReturn(new AuthResult("new.access", "new.refresh", user()));
        given(cookieManager.accessCookie("new.access"))
                .willReturn(ResponseCookie.from("access_token", "new.access").httpOnly(true).build());
        given(cookieManager.refreshCookie("new.refresh"))
                .willReturn(ResponseCookie.from("refresh_token", "new.refresh").httpOnly(true).build());

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "old.refresh")))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().exists("refresh_token"));
    }

    @Test
    @DisplayName("POST /api/auth/logout - 쿠키 삭제(maxAge=0)")
    void logout_clearsCookies() throws Exception {
        given(cookieManager.clearAccessCookie())
                .willReturn(ResponseCookie.from("access_token", "").maxAge(0).build());
        given(cookieManager.clearRefreshCookie())
                .willReturn(ResponseCookie.from("refresh_token", "").maxAge(0).build());

        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", "r")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge("access_token", 0));
    }

    @Test
    @DisplayName("GET /api/auth/me - 인증된 사용자의 정보를 반환")
    void me_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        given(memberService.getMember(1L)).willReturn(user());

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("alice@commerce.com"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }
}
