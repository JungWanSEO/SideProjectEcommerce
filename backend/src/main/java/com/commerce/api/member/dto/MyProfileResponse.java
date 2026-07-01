package com.commerce.api.member.dto;

import com.commerce.api.member.entity.AuthProvider;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 내 정보(회원정보 수정 화면)용 응답 DTO.
 *
 * <p>{@link MemberResponse}보다 풍부하다 — FE가 로컬/소셜을 구분해 UI를 다르게 그리도록
 * {@code provider}(LOCAL/GOOGLE/KAKAO)와 {@code hasPassword}(비번 보유 여부)를 노출한다.
 * (소셜 전용 계정은 {@code hasPassword=false} → "비밀번호 변경" 대신 "비밀번호 설정".)
 */
@Schema(description = "내 정보 응답")
public record MyProfileResponse(
        Long id,
        String email,
        String nickname,
        Role role,
        AuthProvider provider,
        boolean hasPassword,
        LocalDateTime createdAt
) {
    public static MyProfileResponse from(Member member) {
        return new MyProfileResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getRole(),
                member.getProvider(),
                member.hasPassword(),
                member.getCreatedAt()
        );
    }
}
