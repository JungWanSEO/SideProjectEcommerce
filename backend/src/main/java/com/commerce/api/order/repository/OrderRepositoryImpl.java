package com.commerce.api.order.repository;

import static com.commerce.api.order.entity.QOrder.order;

import com.commerce.api.order.dto.OrderSearchCondition;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.QOrderItem;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

/**
 * {@link OrderRepositoryCustom}의 QueryDSL 구현 — {@code MemberRepositoryImpl}/{@code ProductRepositoryImpl}과 같은
 * 패턴(값이 있을 때만 거는 동적 where, 헬퍼가 null이면 무시). 주문 도메인 유일하게 QueryDSL이 없던 구역을 메운다.
 */
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Order> search(OrderSearchCondition condition, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(keywordMatches(condition.keyword()))
                .and(eqMember(condition.memberId()))
                .and(eqStatus(condition.status()))
                .and(createdGoe(condition.from()))
                .and(createdLt(condition.to()))
                .and(amountGoe(condition.minAmount()))
                .and(amountLoe(condition.maxAmount()))
                .and(hasSellerItem(condition.sellerId()));

        List<Order> content = queryFactory
                .selectFrom(order)
                .where(where)
                .orderBy(order.createdAt.desc(), order.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(order.count())
                .from(order)
                .where(where)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    /** 수령인명 부분일치, 또는 키워드가 숫자면 주문번호(id) 정확일치도 함께(OR). */
    private BooleanExpression keywordMatches(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        BooleanExpression byRecipient = order.shippingInfo.recipient.containsIgnoreCase(keyword);
        Long orderId = parseLongOrNull(keyword);
        return orderId != null ? byRecipient.or(order.id.eq(orderId)) : byRecipient;
    }

    private BooleanExpression eqMember(Long memberId) {
        return memberId != null ? order.memberId.eq(memberId) : null;
    }

    private BooleanExpression eqStatus(OrderStatus status) {
        return status != null ? order.status.eq(status) : null;
    }

    private BooleanExpression createdGoe(LocalDate from) {
        return from != null ? order.createdAt.goe(from.atStartOfDay()) : null;
    }

    /** to는 그날 하루를 포함하도록 다음 날 0시 미만으로 건다(포함 경계). */
    private BooleanExpression createdLt(LocalDate to) {
        return to != null ? order.createdAt.lt(to.plusDays(1).atStartOfDay()) : null;
    }

    private BooleanExpression amountGoe(Long minAmount) {
        return minAmount != null ? order.totalPrice.goe(minAmount) : null;
    }

    private BooleanExpression amountLoe(Long maxAmount) {
        return maxAmount != null ? order.totalPrice.loe(maxAmount) : null;
    }

    /**
     * 이 셀러의 상품이 하나라도 든 주문만 — 주문항목에 대한 EXISTS 서브쿼리.
     * (항목은 애그리거트 내부 컬렉션이라 join하면 주문 행이 중복된다 → exists로 걸어 중복 없이 필터.)
     */
    private BooleanExpression hasSellerItem(Long sellerId) {
        if (sellerId == null) {
            return null;
        }
        QOrderItem item = QOrderItem.orderItem;
        return JPAExpressions.selectOne()
                .from(item)
                .where(item.order.eq(order), item.sellerId.eq(sellerId))
                .exists();
    }

    private Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
