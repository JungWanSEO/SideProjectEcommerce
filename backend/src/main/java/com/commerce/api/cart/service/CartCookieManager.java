package com.commerce.api.cart.service;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 게스트 장바구니 토큰 쿠키(#7) — 비로그인 사용자의 장바구니 소유를 식별한다.
 *
 * <p>토큰은 서버가 발급한 <b>추측불가 UUID</b>이고 <b>httpOnly</b> 쿠키에 담아 JS에서 못 읽게 한다(다른 게스트
 * 카트 탈취=IDOR 방지). secure·sameSite는 인증 쿠키와 동일 정책({@code app.cookie.*})을 따른다. 로그인 시
 * 병합 후 이 쿠키를 지운다(maxAge=0).
 */
@Component
public class CartCookieManager {

    public static final String CART_TOKEN_COOKIE = "cart_token";

    /** 게스트 카트 보존 기간 — 30일(장바구니는 오래 유지되는 게 UX상 유리). */
    private static final long MAX_AGE_SEC = 60L * 60 * 24 * 30;

    private final boolean secure;
    private final String sameSite;

    public CartCookieManager(
            @Value("${app.cookie.secure:false}") boolean secure,
            @Value("${app.cookie.same-site:Lax}") String sameSite) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    /** 새 게스트 토큰(추측불가 UUID). */
    public String newToken() {
        return UUID.randomUUID().toString();
    }

    public ResponseCookie issue(String token) {
        return build(token, MAX_AGE_SEC);
    }

    /** 로그인 병합 후/정리용: 값 비우고 maxAge=0 → 브라우저가 즉시 삭제. */
    public ResponseCookie clear() {
        return build("", 0);
    }

    private ResponseCookie build(String value, long maxAgeSec) {
        return ResponseCookie.from(CART_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSec))
                .build();
    }
}
