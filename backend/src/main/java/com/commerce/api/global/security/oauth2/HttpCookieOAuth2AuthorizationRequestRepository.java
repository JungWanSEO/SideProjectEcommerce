package com.commerce.api.global.security.oauth2;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.Optional;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * OAuth2 인가 요청(state·nonce·PKCE·redirect-uri 등)을 <b>세션 대신 httpOnly 쿠키</b>에 저장한다.
 *
 * <p><b>왜:</b> 우리 앱은 {@code SessionCreationPolicy.STATELESS}라 기본 세션 기반 저장소를 쓸 수 없다.
 * OAuth2 로그인은 구글로 리다이렉트했다가 콜백으로 돌아올 때 {@code state}를 대조해야 하는데, 그 짧은 왕복
 * 동안의 상태를 쿠키에 담아 <b>서버 세션 없이</b> 처리한다(스테이트리스 설계 유지). 이 쿠키는 BE 도메인
 * 내부에서만 오가고(구글→BE 콜백은 top-level GET → SameSite=Lax로 전송됨), 콜백에서 즉시 제거된다.
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_auth_request";
    private static final int MAX_AGE_SEC = 180;   // 인가 왕복은 수십 초 — 3분이면 충분

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return readCookie(request).map(this::deserialize).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            deleteCookie(response);   // Spring이 null을 넘기면 = 저장소 비우기
            return;
        }
        Cookie cookie = new Cookie(COOKIE_NAME, serialize(authorizationRequest));
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(MAX_AGE_SEC);
        response.addCookie(cookie);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        OAuth2AuthorizationRequest authRequest = loadAuthorizationRequest(request);
        deleteCookie(response);   // 콜백에서 대조 후 즉시 제거(1회용)
        return authRequest;
    }

    private Optional<Cookie> readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private void deleteCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);   // 즉시 삭제
        response.addCookie(cookie);
    }

    /** OAuth2AuthorizationRequest(Serializable)를 Java 직렬화 → Base64(URL-safe)로 쿠키 값에 담는다. */
    private String serialize(OAuth2AuthorizationRequest authRequest) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(authRequest);
            oos.flush();
            return Base64.getUrlEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("OAuth2 인가 요청 직렬화 실패", e);
        }
    }

    /** 손상/구버전 쿠키는 null로 취급 → 프레임워크가 새 인가 요청을 시작한다(로그인 재시도). */
    private OAuth2AuthorizationRequest deserialize(Cookie cookie) {
        try (ByteArrayInputStream bis =
                        new ByteArrayInputStream(Base64.getUrlDecoder().decode(cookie.getValue()));
                ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (OAuth2AuthorizationRequest) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }
}
