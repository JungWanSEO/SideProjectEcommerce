package com.commerce.api.cart.service;

/**
 * 장바구니 소유자(#7) — 로그인 회원({@code memberId}) 또는 게스트({@code token}, 쿠키). <b>정확히 하나만</b> 설정.
 * 컨트롤러가 요청 컨텍스트(SecurityContext 또는 cart_token 쿠키)에서 만들어 서비스에 넘긴다.
 */
public record CartOwner(Long memberId, String token) {

    public static CartOwner member(Long memberId) {
        return new CartOwner(memberId, null);
    }

    public static CartOwner guest(String token) {
        return new CartOwner(null, token);
    }

    public boolean isMember() {
        return memberId != null;
    }
}
