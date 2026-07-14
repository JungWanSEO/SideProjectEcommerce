package com.commerce.api.member.dto;

import com.commerce.api.member.entity.Role;

/**
 * 회원 검색 조건 (ADMIN 회원 목록). 값이 없는 필드는 조건에서 제외된다(동적 where).
 *
 * @param keyword 이메일 또는 닉네임 부분일치(대소문자 무시). CS가 "이 사람" 찾을 때 쓰는 단일 검색창.
 * @param role    권한 필터(USER/SELLER/ADMIN). null이면 전체.
 */
public record MemberSearchCondition(
        String keyword,
        Role role
) {
}
