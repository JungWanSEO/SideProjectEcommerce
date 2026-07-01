package com.commerce.api.auth.service;

import com.commerce.api.auth.dto.AuthResult;
import com.commerce.api.auth.dto.LoginRequest;
import com.commerce.api.auth.entity.RefreshToken;
import com.commerce.api.auth.repository.RefreshTokenRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.global.security.JwtTokenProvider;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 비즈니스 로직.
 * - 로그인: 이메일/비밀번호 검증 → 액세스 + 리프레시 토큰 발급(리프레시는 DB 저장).
 * - 재발급: 리프레시 토큰 검증 → 새 액세스 + 새 리프레시 발급(회전).
 * - 로그아웃: 저장된 리프레시 토큰 폐기.
 *
 * <p>토큰은 컨트롤러가 httpOnly 쿠키로 굽는다. 여기서는 토큰 문자열 + 유저 정보를 {@link AuthResult}로 돌려준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final String INVALID_LOGIN = "이메일 또는 비밀번호가 올바르지 않습니다.";
    private static final String INVALID_REFRESH = "유효하지 않은 리프레시 토큰입니다.";

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public AuthResult login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN));

        // 소셜 전용 계정은 password가 null → matches(raw, null)=false → 로컬 로그인은 자연히 차단(401).
        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN);
        }

        return issueTokens(member);
    }

    /**
     * 회원에게 access·refresh 토큰을 발급하고 refresh를 저장(멤버당 1개 upsert)한다.
     * 로컬 로그인과 소셜 로그인(OAuth2 성공 핸들러)이 공용으로 쓰는 발급 지점 — 인증 "주체"만 다르고 발급은 동일.
     */
    @Transactional
    public AuthResult issueTokens(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(member.getId());
        saveOrUpdateRefreshToken(member.getId(), refreshToken);
        return new AuthResult(accessToken, refreshToken, MemberResponse.from(member));
    }

    @Transactional
    public AuthResult refresh(String providedToken) {
        if (providedToken == null || !jwtTokenProvider.validate(providedToken)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH);
        }

        Long memberId = jwtTokenProvider.getMemberId(providedToken);
        RefreshToken stored = refreshTokenRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH));

        // 저장된 토큰과 다르면 이미 회전(또는 폐기)된 것 → 거부
        if (!stored.getToken().equals(providedToken)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH));

        String newAccessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getRole());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(member.getId());
        stored.update(newRefreshToken);   // 회전(rotation)

        return new AuthResult(newAccessToken, newRefreshToken, MemberResponse.from(member));
    }

    /**
     * 로그아웃: 리프레시 쿠키로 본인을 식별해 저장된 리프레시 토큰을 폐기한다.
     * 토큰이 없거나 유효하지 않아도 조용히 통과(멱등) — 쿠키 삭제는 컨트롤러가 항상 수행.
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || !jwtTokenProvider.validate(refreshToken)) {
            return;
        }
        Long memberId = jwtTokenProvider.getMemberId(refreshToken);
        refreshTokenRepository.findByMemberId(memberId)
                .ifPresent(refreshTokenRepository::delete);
    }

    private void saveOrUpdateRefreshToken(Long memberId, String token) {
        refreshTokenRepository.findByMemberId(memberId)
                .ifPresentOrElse(
                        existing -> existing.update(token),
                        () -> refreshTokenRepository.save(
                                RefreshToken.builder().memberId(memberId).token(token).build()));
    }
}
