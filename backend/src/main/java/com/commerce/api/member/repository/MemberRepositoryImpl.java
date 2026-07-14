package com.commerce.api.member.repository;

import static com.commerce.api.member.entity.QMember.member;

import com.commerce.api.member.dto.MemberSearchCondition;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

/**
 * {@link MemberRepositoryCustom}의 QueryDSL 구현. ({@code AuditLogRepositoryImpl}과 같은 패턴 —
 * 값이 있을 때만 거는 동적 where, 헬퍼가 null을 반환하면 그 조건은 무시된다.)
 *
 * <p>정렬은 <b>가입 최신순 고정</b>(같은 시각이면 id로 안정 정렬) — 회원 목록은 "최근 가입한 사람"이
 * 운영상 기본 관심사라 감사 로그와 같은 결정을 따른다.
 */
@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Member> search(MemberSearchCondition condition, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(keywordContains(condition.keyword()))
                .and(eqRole(condition.role()));

        List<Member> content = queryFactory
                .selectFrom(member)
                .where(where)
                .orderBy(member.createdAt.desc(), member.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(member.count())
                .from(member)
                .where(where)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    /** 검색창 하나로 이메일·닉네임을 함께 훑는다(부분일치·대소문자 무시). */
    private BooleanExpression keywordContains(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return member.email.containsIgnoreCase(keyword)
                .or(member.nickname.containsIgnoreCase(keyword));
    }

    private BooleanExpression eqRole(Role role) {
        return role != null ? member.role.eq(role) : null;
    }
}
