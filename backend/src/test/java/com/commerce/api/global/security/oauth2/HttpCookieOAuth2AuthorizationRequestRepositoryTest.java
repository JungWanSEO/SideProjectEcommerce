package com.commerce.api.global.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 쿠키 기반 인가요청 저장소 검증 — 세션 없이(STATELESS) 인가 요청을 쿠키로 왕복 저장/복원하는지.
 * (실제 OAuth 흐름은 구글 자격증명이 있어야 하지만, 이 직렬화 왕복은 구글 없이 단위 검증 가능.)
 */
class HttpCookieOAuth2AuthorizationRequestRepositoryTest {

    private final HttpCookieOAuth2AuthorizationRequestRepository repo =
            new HttpCookieOAuth2AuthorizationRequestRepository();

    private OAuth2AuthorizationRequest sample() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client-123")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(Set.of("openid", "email", "profile"))
                .state("state-xyz")
                .attributes(Map.of("registration_id", "google"))
                .build();
    }

    @Test
    @DisplayName("저장한 인가 요청을 쿠키에서 그대로 복원한다(세션 없이)")
    void saveThenLoadRoundTrip() {
        MockHttpServletResponse saveResponse = new MockHttpServletResponse();
        repo.saveAuthorizationRequest(sample(), new MockHttpServletRequest(), saveResponse);

        Cookie saved = saveResponse.getCookie("oauth2_auth_request");
        assertThat(saved).isNotNull();
        assertThat(saved.getValue()).isNotBlank();
        assertThat(saved.isHttpOnly()).isTrue();

        MockHttpServletRequest loadRequest = new MockHttpServletRequest();
        loadRequest.setCookies(new Cookie("oauth2_auth_request", saved.getValue()));

        OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(loadRequest);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getState()).isEqualTo("state-xyz");
        assertThat(loaded.getClientId()).isEqualTo("client-123");
        assertThat(loaded.getScopes()).contains("openid", "email", "profile");
    }

    @Test
    @DisplayName("remove는 인가 요청을 반환하고 쿠키를 삭제(maxAge=0)한다")
    void removeReturnsAndDeletes() {
        MockHttpServletResponse saveResponse = new MockHttpServletResponse();
        repo.saveAuthorizationRequest(sample(), new MockHttpServletRequest(), saveResponse);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("oauth2_auth_request", saveResponse.getCookie("oauth2_auth_request").getValue()));
        MockHttpServletResponse removeResponse = new MockHttpServletResponse();

        OAuth2AuthorizationRequest removed = repo.removeAuthorizationRequest(request, removeResponse);
        assertThat(removed).isNotNull();
        assertThat(removed.getState()).isEqualTo("state-xyz");
        assertThat(removeResponse.getCookie("oauth2_auth_request").getMaxAge()).isZero();   // 1회용 삭제
    }

    @Test
    @DisplayName("쿠키가 없으면 null(진행 중인 인가 요청 없음)")
    void loadWithoutCookieReturnsNull() {
        assertThat(repo.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
    }
}
