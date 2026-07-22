package com.commerce.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JwtAuthenticationFilter 단위 테스트 — 토큰 해석(헤더/쿠키 우선순위)과 인증 컨텍스트 세팅.
 *
 * <p>JaCoCo에서 이 필터가 통합 테스트로만 스쳐 지나가 분기(무토큰·무효·role null·쿠키 경로)가
 * 직접 검증된 적이 없었다. principal=회원ID, 권한=ROLE_&lt;role&gt; 규약을 못박는다.
 * SecurityContext는 스레드로컬이라 테스트 간 새지 않도록 매번 clear한다.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(jwtTokenProvider);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("유효 토큰(Authorization 헤더) → principal=회원ID + ROLE_<role> 인증 세팅, 체인 계속")
    void validToken_viaHeader_setsAuthentication() throws Exception {
        given(jwtTokenProvider.validate("tok")).willReturn(true);
        given(jwtTokenProvider.getRole("tok")).willReturn("ADMIN");
        given(jwtTokenProvider.getMemberId("tok")).willReturn(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilterInternal(request, response, chain);

        Authentication auth = currentAuth();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(7L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        verify(chain).doFilter(request, response);   // 필터 체인은 항상 이어진다
    }

    @Test
    @DisplayName("유효 토큰(httpOnly 쿠키 access_token) → 인증 세팅")
    void validToken_viaCookie_setsAuthentication() throws Exception {
        given(jwtTokenProvider.validate("ck")).willReturn(true);
        given(jwtTokenProvider.getRole("ck")).willReturn("USER");
        given(jwtTokenProvider.getMemberId("ck")).willReturn(3L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookieManager.ACCESS_COOKIE, "ck"));

        filter().doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(currentAuth().getPrincipal()).isEqualTo(3L);
        assertThat(currentAuth().getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("헤더가 쿠키보다 우선 — 둘 다 있으면 헤더 토큰으로 해석")
    void header_takesPriorityOverCookie() throws Exception {
        given(jwtTokenProvider.validate("header-tok")).willReturn(true);
        given(jwtTokenProvider.getRole("header-tok")).willReturn("ADMIN");
        given(jwtTokenProvider.getMemberId("header-tok")).willReturn(1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header-tok");
        request.setCookies(new Cookie(AuthCookieManager.ACCESS_COOKIE, "cookie-tok"));

        filter().doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

        // 쿠키 토큰이 아니라 헤더 토큰으로 검증됐다(cookie-tok은 validate조차 호출되지 않음 — strict stub이 보장)
        assertThat(currentAuth().getPrincipal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("토큰 없음 → 인증 없음(체인은 계속)")
    void noToken_noAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilterInternal(request, response, chain);

        assertThat(currentAuth()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("무효 토큰 → 인증 없음")
    void invalidToken_noAuthentication() throws Exception {
        given(jwtTokenProvider.validate("bad")).willReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");

        filter().doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(currentAuth()).isNull();
    }

    @Test
    @DisplayName("토큰은 유효하나 role이 null(리프레시 등) → 인증으로 인정하지 않음")
    void validToken_butNullRole_noAuthentication() throws Exception {
        given(jwtTokenProvider.validate("refresh")).willReturn(true);
        given(jwtTokenProvider.getRole("refresh")).willReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer refresh");

        filter().doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(currentAuth()).isNull();
    }
}
