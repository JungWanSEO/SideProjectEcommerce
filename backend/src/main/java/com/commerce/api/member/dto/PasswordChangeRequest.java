package com.commerce.api.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경/설정 요청.
 * {@code currentPassword}는 <b>비번이 이미 있는 계정</b>(로컬·비번설정한 소셜)에서만 필요 — 소셜 전용
 * 계정의 최초 '설정'에선 없어도 된다(그래서 nullable). 검증은 서비스에서 계정 상태에 따라 수행.
 */
@Schema(description = "비밀번호 변경/설정 요청")
public record PasswordChangeRequest(

        @Schema(description = "현재 비밀번호(비번이 있는 계정만 필요)", nullable = true)
        String currentPassword,

        @Schema(description = "새 비밀번호(8자 이상)", example = "newpassword123")
        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        String newPassword
) {
}
