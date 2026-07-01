package com.commerce.api.global.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.api.auth.dto.AuthResult;
import com.commerce.api.auth.service.AuthService;
import com.commerce.api.global.security.AuthCookieManager;
import com.commerce.api.member.entity.AuthProvider;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.service.MemberService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 소셜 로그인 성공 핸들러 — provider별 속성 추출 + 쿠키 발급 + 리다이렉트 검증.
 * (특히 카카오 id(Long)→providerId 문자열 변환의 ClassCastException 회귀 방지.)
 */
class OAuth2LoginSuccessHandlerTest {

    private final MemberService memberService = mock(MemberService.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuthCookieManager cookieManager =
            new AuthCookieManager(false, "Lax", 1_800_000, 1_209_600_000);
    private final OAuth2LoginSuccessHandler handler =
            new OAuth2LoginSuccessHandler(memberService, authService, cookieManager, "http://localhost:3000");

    private OAuth2AuthenticationToken token(Map<String, Object> attrs, String nameKey, String registrationId) {
        OAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attrs, nameKey);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), registrationId);
    }

    @Test
    @DisplayName("카카오: id(Long)를 providerId 문자열로, kakao_account.profile.nickname을 이름으로(email 없음)")
    void kakao_extractsLongIdAndNestedNickname() throws Exception {
        Member member = mock(Member.class);
        when(memberService.findOrCreateSocialMember(eq(AuthProvider.KAKAO), eq("4321"), isNull(), eq("길동")))
                .thenReturn(member);
        when(authService.issueTokens(member)).thenReturn(new AuthResult("access-tok", "refresh-tok", null));

        // ⚠️ id=Long — 과거 String.valueOf(getAttribute)가 char[] 오버로드를 골라 터지던 케이스
        var token = token(Map.of(
                "id", 4321L,
                "kakao_account", Map.of("profile", Map.of("nickname", "길동")),
                "properties", Map.of("nickname", "길동")), "id", "kakao");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

        verify(memberService).findOrCreateSocialMember(AuthProvider.KAKAO, "4321", null, "길동");
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000");
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(h -> h.contains("access_token=access-tok"));
    }

    @Test
    @DisplayName("구글: sub/email/name을 그대로 추출")
    void google_extractsSubEmailName() throws Exception {
        Member member = mock(Member.class);
        when(memberService.findOrCreateSocialMember(
                eq(AuthProvider.GOOGLE), eq("sub-123"), eq("a@b.com"), eq("Alice"))).thenReturn(member);
        when(authService.issueTokens(member)).thenReturn(new AuthResult("access-tok", "refresh-tok", null));

        var token = token(Map.of("sub", "sub-123", "email", "a@b.com", "name", "Alice"), "sub", "google");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, token);

        verify(memberService).findOrCreateSocialMember(AuthProvider.GOOGLE, "sub-123", "a@b.com", "Alice");
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000");
    }
}
