package com.commerce.api.global.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * JWT를 담는 httpOnly 쿠키를 만들고/지운다.
 *
 * <p>토큰 보관 전략: access·refresh 모두 <b>httpOnly 쿠키</b>에 둔다.
 * <ul>
 *   <li><b>httpOnly</b> → JS(document.cookie)에서 읽을 수 없음 → XSS로 토큰 탈취 방지.</li>
 *   <li><b>SameSite</b> → 같은 사이트 요청에만 쿠키 전송(CSRF 차단). 로컬은 {@code Lax}. <b>FE/BE 도메인이
 *       다른 배포</b>(예: vercel.app ↔ render.com)에선 크로스사이트라 {@code None}이어야 쿠키가 실린다.</li>
 *   <li><b>secure</b> → https에서만 전송. 로컬(http) false, 운영(https) true. (SameSite=None은 Secure 필수.)</li>
 * </ul>
 *
 * <p>secure·sameSite는 {@code app.cookie.*}로 외부화 — 로컬 기본은 {@code false/Lax}, 운영은 env로
 * {@code APP_COOKIE_SECURE=true}, {@code APP_COOKIE_SAME_SITE=None}을 준다(크로스도메인 로그인).
 */
@Component
public class AuthCookieManager {

    /** @CookieValue 등에서 참조할 수 있도록 public 상수. */
    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";

    private final boolean secure;
    private final String sameSite;
    private final long accessMaxAgeSec;
    private final long refreshMaxAgeSec;

    public AuthCookieManager(
            @Value("${app.cookie.secure:false}") boolean secure,
            @Value("${app.cookie.same-site:Lax}") String sameSite,
            @Value("${jwt.access-token-validity-ms}") long accessValidityMs,
            @Value("${jwt.refresh-token-validity-ms}") long refreshValidityMs) {
        this.secure = secure;
        this.sameSite = sameSite;
        this.accessMaxAgeSec = accessValidityMs / 1000;
        this.refreshMaxAgeSec = refreshValidityMs / 1000;
    }

    public ResponseCookie accessCookie(String token) {
        return build(ACCESS_COOKIE, token, accessMaxAgeSec);
    }

    public ResponseCookie refreshCookie(String token) {
        return build(REFRESH_COOKIE, token, refreshMaxAgeSec);
    }

    /** 로그아웃/만료용: 값 비우고 maxAge=0 → 브라우저가 즉시 삭제. */
    public ResponseCookie clearAccessCookie() {
        return build(ACCESS_COOKIE, "", 0);
    }

    public ResponseCookie clearRefreshCookie() {
        return build(REFRESH_COOKIE, "", 0);
    }

    private ResponseCookie build(String name, String value, long maxAgeSec) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSec))
                .build();
    }
}
