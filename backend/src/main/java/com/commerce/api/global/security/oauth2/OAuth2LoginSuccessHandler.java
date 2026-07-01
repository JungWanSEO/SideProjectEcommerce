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
import java.util.Map;
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
 * <p>흐름: 제공자별로 사용자 속성 추출 → (provider, providerId)로 회원 find-or-create →
 * 기존 발급 파이프라인({@link AuthService#issueTokens})으로 우리 JWT(access·refresh)를 httpOnly 쿠키에 심고 →
 * 프론트로 리다이렉트. 프론트는 로드 시 {@code /api/auth/me}로 로그인 상태를 인지한다.
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
        String registrationId = token.getAuthorizedClientRegistrationId();   // "google" | "kakao"
        AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase());
        SocialUserInfo info = extract(registrationId, token.getPrincipal());

        Member member = memberService.findOrCreateSocialMember(
                provider, info.providerId(), info.email(), info.name());
        AuthResult result = authService.issueTokens(member);   // 로컬 로그인과 동일한 발급 지점 재사용

        response.addHeader(HttpHeaders.SET_COOKIE, cookieManager.accessCookie(result.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookieManager.refreshCookie(result.refreshToken()).toString());
        response.sendRedirect(successRedirectUrl);
    }

    /** 제공자별 속성 구조가 달라 분기 추출한다(구글 OIDC 평면 vs 카카오 중첩). */
    private SocialUserInfo extract(String registrationId, OAuth2User principal) {
        return switch (registrationId) {
            case "google" -> new SocialUserInfo(
                    principal.getAttribute("sub"),        // 구글 OIDC 고유 ID
                    principal.getAttribute("email"),
                    principal.getAttribute("name"));
            case "kakao" -> extractKakao(principal);
            default -> throw new IllegalStateException("지원하지 않는 소셜 provider: " + registrationId);
        };
    }

    /**
     * 카카오 user-info(/v2/user/me) 구조:
     * {@code { id, properties.nickname, kakao_account.profile.nickname, kakao_account.email(동의 시) } }.
     * email-free(닉네임/프로필 스코프)면 email은 없다 → null(회원 생성 시 플레이스홀더로 대체).
     */
    private SocialUserInfo extractKakao(OAuth2User principal) {
        // principal.getName() = user-name-attribute("id") 값의 문자열. ⚠️ String.valueOf(getAttribute("id"))는
        // getAttribute의 제네릭 <A>A 추론이 char[] 오버로드를 골라 ClassCastException(Long→char[])이 난다 → getName() 사용.
        String providerId = principal.getName();
        String email = null;
        String nickname = null;
        if (principal.getAttribute("kakao_account") instanceof Map<?, ?> account) {
            Object e = account.get("email");
            email = e != null ? e.toString() : null;
            if (account.get("profile") instanceof Map<?, ?> profile && profile.get("nickname") != null) {
                nickname = profile.get("nickname").toString();
            }
        }
        if (nickname == null && principal.getAttribute("properties") instanceof Map<?, ?> props
                && props.get("nickname") != null) {
            nickname = props.get("nickname").toString();   // 폴백: properties.nickname
        }
        return new SocialUserInfo(providerId, email, nickname);
    }

    /** 제공자별 추출 결과(내부 전달용). */
    private record SocialUserInfo(String providerId, String email, String name) {
    }
}
