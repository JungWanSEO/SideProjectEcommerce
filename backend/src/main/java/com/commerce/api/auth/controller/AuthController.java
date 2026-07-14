package com.commerce.api.auth.controller;

import com.commerce.api.auth.dto.AuthResult;
import com.commerce.api.auth.dto.LoginRequest;
import com.commerce.api.auth.service.AuthService;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.ratelimit.RateLimit;
import com.commerce.api.global.security.AuthCookieManager;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API. 토큰은 httpOnly 쿠키로 주고받는다(클라이언트 JS는 토큰을 직접 보지 않음).
 * - POST /api/auth/login    로그인 → access·refresh 쿠키 set, body엔 유저 정보
 * - POST /api/auth/refresh  쿠키의 refresh로 회전 → 새 쿠키 set
 * - POST /api/auth/logout   refresh 폐기 + 쿠키 삭제
 * - GET  /api/auth/me       현재 로그인 유저 (쿠키의 access로 인증)
 */
@Tag(name = "인증(Auth)", description = "로그인 / 토큰 재발급 / 로그아웃 / 내 정보 API (httpOnly 쿠키 기반)")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final MemberService memberService;
    private final AuthCookieManager cookieManager;

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인. access·refresh 토큰을 httpOnly 쿠키로 내려주고 body엔 유저 정보. 실패 시 401.")
    @RateLimit(key = "login", limit = 5, by = "#request.email()")   // 무차별 대입 방지: 이메일당 1분 5회
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<MemberResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieManager.accessCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookieManager.refreshCookie(result.refreshToken()).toString())
                .body(ApiResponse.success("로그인 성공", result.user()));
    }

    @Operation(summary = "토큰 재발급", description = "refresh 쿠키로 새 access·refresh 토큰을 발급(회전)해 쿠키로 내려준다. 유효하지 않으면 401.")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<MemberResponse>> refresh(
            @CookieValue(name = AuthCookieManager.REFRESH_COOKIE, required = false) String refreshToken) {
        AuthResult result = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieManager.accessCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookieManager.refreshCookie(result.refreshToken()).toString())
                .body(ApiResponse.success("토큰이 재발급되었습니다.", result.user()));
    }

    @Operation(summary = "로그아웃", description = "저장된 refresh 토큰을 폐기하고 인증 쿠키를 삭제한다. (멱등)")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = AuthCookieManager.REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieManager.clearAccessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, cookieManager.clearRefreshCookie().toString())
                .body(ApiResponse.<Void>success("로그아웃되었습니다.", null));
    }

    @Operation(summary = "내 정보", description = "쿠키의 access 토큰으로 현재 로그인한 회원 정보를 반환한다. 미인증 시 401.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MemberResponse>> me() {
        MemberResponse user = memberService.getMember(SecurityUtil.getCurrentMemberId());
        return ResponseEntity.ok(ApiResponse.success(user));
    }
}
