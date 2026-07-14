package com.commerce.api.member.repository;

import com.commerce.api.member.dto.MemberSearchCondition;
import com.commerce.api.member.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 회원 동적 검색(QueryDSL) — 어드민 회원 목록용. 구현은 {@link MemberRepositoryImpl}.
 */
public interface MemberRepositoryCustom {

    /** 키워드(이메일·닉네임)·권한으로 회원을 검색한다(가입 최신순). */
    Page<Member> search(MemberSearchCondition condition, Pageable pageable);
}
