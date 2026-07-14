package com.commerce.api.member.dto;

import com.commerce.api.member.entity.Role;
import jakarta.validation.constraints.NotNull;

/**
 * 회원 권한 변경 요청 (ADMIN) — PATCH /api/members/{id}/role.
 *
 * <p>SELLER는 여기로 지정할 수 없다(400) — 셀러 연결이 필요하므로 셀러 운영자 지정 API를 쓴다.
 */
public record MemberRoleUpdateRequest(
        @NotNull(message = "권한은 필수입니다.")
        Role role
) {
}
