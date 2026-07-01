package com.commerce.api.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 회원정보(프로필) 수정 요청 — 현재는 닉네임만. */
@Schema(description = "회원정보 수정 요청")
public record MemberUpdateRequest(

        @Schema(description = "닉네임(30자 이하)", example = "앨리스")
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 30, message = "닉네임은 30자 이하여야 합니다.")
        String nickname
) {
}
