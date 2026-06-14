package com.commerce.api.settlement.repository;

import static com.commerce.api.settlement.entity.QSettlementEntry.settlementEntry;

import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * {@link SettlementRepositoryCustom}의 QueryDSL 구현 — 셀러/상태/기간 동적 필터 + 셀러별 집계.
 *
 * <p>{@code QSettlementEntry.settlementEntry}는 빌드 시 생성되는 쿼리 타입. 동적 where는
 * 값이 있을 때만 거는 헬퍼(없으면 null → BooleanBuilder가 무시)로 조립한다(ProductRepositoryImpl과 동일).
 */
@RequiredArgsConstructor
public class SettlementRepositoryImpl implements SettlementRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<SettlementEntry> search(SettlementSearchCondition condition, Pageable pageable) {
        BooleanBuilder where = whereOf(condition);

        // 정산 목록은 최신순(id desc) 고정 — 어드민 화면이 별도 정렬을 쓰지 않는다.
        List<SettlementEntry> content = queryFactory
                .selectFrom(settlementEntry)
                .where(where)
                .orderBy(settlementEntry.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(settlementEntry.count())
                .from(settlementEntry)
                .where(where)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    @Override
    public List<SellerSettlementSummary> summarizeBySeller(SettlementSearchCondition condition) {
        // 셀러별 group-by 집계. sellerName은 다른 애그리거트라 여기서 조인하지 않고(ID 참조 원칙)
        // null로 두고 서비스가 enrich한다(ProductService.brandName 패턴).
        return queryFactory
                .select(Projections.constructor(SellerSettlementSummary.class,
                        settlementEntry.sellerId,
                        Expressions.nullExpression(String.class),
                        settlementEntry.count(),
                        settlementEntry.grossAmount.sum(),
                        settlementEntry.fee.sum(),
                        settlementEntry.platformFee.sum(),
                        settlementEntry.netAmount.sum()))
                .from(settlementEntry)
                .where(whereOf(condition))
                .groupBy(settlementEntry.sellerId)
                .fetch();
    }

    /** 셀러·상태·기간 동적 where — 값이 없는 조건은 null로 두어 무시. */
    private BooleanBuilder whereOf(SettlementSearchCondition c) {
        return new BooleanBuilder()
                .and(c.sellerId() != null ? settlementEntry.sellerId.eq(c.sellerId()) : null)
                .and(c.status() != null ? settlementEntry.status.eq(c.status()) : null)
                .and(c.from() != null ? settlementEntry.settledDate.goe(c.from()) : null)
                .and(c.to() != null ? settlementEntry.settledDate.loe(c.to()) : null);
    }
}
