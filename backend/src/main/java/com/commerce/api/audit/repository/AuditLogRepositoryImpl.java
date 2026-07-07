package com.commerce.api.audit.repository;

import static com.commerce.api.audit.entity.QAuditLog.auditLog;

import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.entity.AuditLog;
import com.commerce.api.audit.entity.AuditResult;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

/**
 * {@link AuditLogRepositoryCustom}의 QueryDSL 구현.
 *
 * <p>{@code ProductRepositoryImpl}과 같은 패턴: 값이 있을 때만 거는 동적 where(헬퍼가 null 반환하면 무시).
 * 정렬은 감사 성격상 항상 최신순으로 고정한다(같은 시각이면 id로 안정 정렬).
 */
@RequiredArgsConstructor
public class AuditLogRepositoryImpl implements AuditLogRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<AuditLog> search(AuditLogSearchCondition condition, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(eqActor(condition.actorMemberId()))
                .and(eqAction(condition.action()))
                .and(eqTargetType(condition.targetType()))
                .and(eqResult(condition.result()))
                .and(createdGoe(condition.from()))
                .and(createdLt(condition.to()));

        List<AuditLog> content = queryFactory
                .selectFrom(auditLog)
                .where(where)
                .orderBy(auditLog.createdAt.desc(), auditLog.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(auditLog.count())
                .from(auditLog)
                .where(where)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    // --- 값이 없으면 null → BooleanBuilder가 그 조건을 무시 ---
    private BooleanExpression eqActor(Long actorId) {
        return actorId != null ? auditLog.actorMemberId.eq(actorId) : null;
    }

    private BooleanExpression eqAction(String action) {
        return StringUtils.hasText(action) ? auditLog.action.eq(action) : null;
    }

    private BooleanExpression eqTargetType(String targetType) {
        return StringUtils.hasText(targetType) ? auditLog.targetType.eq(targetType) : null;
    }

    private BooleanExpression eqResult(AuditResult result) {
        return result != null ? auditLog.result.eq(result) : null;
    }

    private BooleanExpression createdGoe(LocalDateTime from) {
        return from != null ? auditLog.createdAt.goe(from) : null;
    }

    private BooleanExpression createdLt(LocalDateTime to) {
        return to != null ? auditLog.createdAt.lt(to) : null;
    }
}
