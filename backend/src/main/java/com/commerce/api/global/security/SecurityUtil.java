package com.commerce.api.global.security;

import com.commerce.api.global.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContext에서 현재 로그인 회원 ID를 꺼내는 헬퍼.
 * JwtAuthenticationFilter가 principal로 회원 ID(Long)를 넣어둔다.
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static Long getCurrentMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long memberId)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        return memberId;
    }

    /**
     * 현재 로그인 회원 ID, 비로그인이면 null(#7 게스트 카트 — 공개 엔드포인트에서 회원/게스트 판별용).
     * {@link #getCurrentMemberId()}와 달리 인증이 없어도 예외를 던지지 않는다.
     */
    public static Long getCurrentMemberIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (authentication != null && authentication.getPrincipal() instanceof Long memberId) ? memberId : null;
    }

    /** 현재 로그인 사용자가 ADMIN 권한을 가졌는지. (권한 문자열은 SecurityConfig·JWT 필터가 쓰는 "ROLE_ADMIN") */
    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
