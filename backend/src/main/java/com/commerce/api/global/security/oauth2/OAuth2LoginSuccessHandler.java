package com.commerce.api.global.security.oauth2;

import com.commerce.api.auth.dto.AuthResult;
import com.commerce.api.auth.service.AuthService;
import com.commerce.api.global.security.AuthCookieManager;
import com.commerce.api.member.entity.AuthProvider;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 소셜 로그인 성공 핸들러 — 인증 "주체"만 IdP로 갈아끼우고, 그 이후는 로컬 로그인과 동일하다(architecture.md §12).
 *
 * <p>흐름: (provider, providerId)로 회원 find-or-create → 기존 발급 파이프라인({@link AuthService#issueTokens})으로
 * 우리 JWT(access·refresh)를 httpOnly 쿠키에 심고 → 프론트로 리다이렉트. 프론트는 로드 시 {@code /api/auth/me}로
 * 로그인 상태를 인지한다(토큰은 쿠키에 있어 JS가 직접 안 봄).
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final MemberService memberService;
    private final AuthService authService;
    private final AuthCookieManager cookieManager;
    private final String successRedirectUrl;

    public OAuth2LoginSuccessHandler(MemberService memberService, AuthService authService,
            AuthCookieManager cookieManager,
            @Value("${app.oauth2.success-redirect-url:http://localhost:3000}") String successRedirectUrl) {
        this.memberService = memberService;
        this.authService = authService;
        this.cookieManager = cookieManager;
        this.successRedirectUrl = successRedirectUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        // registrationId("google") → AuthProvider.GOOGLE
        AuthProvider provider = AuthProvider.valueOf(token.getAuthorizedClientRegistrationId().toUpperCase());
        OAuth2User principal = token.getPrincipal();

        String providerId = principal.getAttribute("sub");     // 구글 OIDC 고유 ID(sub) = 안정적 1차 식별자
        String email = principal.getAttribute("email");
        String name = principal.getAttribute("name");

        Member member = memberService.findOrCreateSocialMember(provider, providerId, email, name);
        AuthResult result = authService.issueTokens(member);   // 로컬 로그인과 동일한 발급 지점 재사용

        response.addHeader(HttpHeaders.SET_COOKIE, cookieManager.accessCookie(result.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookieManager.refreshCookie(result.refreshToken()).toString());
        response.sendRedirect(successRedirectUrl);
    }
}
