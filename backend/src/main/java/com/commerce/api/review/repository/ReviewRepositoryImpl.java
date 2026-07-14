package com.commerce.api.review.repository;

import static com.commerce.api.review.entity.QReview.review;

import com.commerce.api.review.dto.ReviewSearchCondition;
import com.commerce.api.review.entity.Review;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link ReviewRepositoryCustom}의 QueryDSL 구현.
 *
 * <p>정렬은 Pageable의 Sort를 그대로 받아 {@code createdAt}(최신순)·{@code rating}(평점 높은/낮은순)을 지원한다
 * ({@code ProductRepositoryImpl.toOrderSpecifiers}와 같은 PathBuilder 방식). 같은 값이면 id로 안정 정렬해
 * 페이지를 넘겨도 같은 리뷰가 두 번 나오지 않게 한다.
 */
@RequiredArgsConstructor
public class ReviewRepositoryImpl implements ReviewRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Review> search(Long productId, ReviewSearchCondition condition, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder()
                .and(review.productId.eq(productId))
                .and(eqRating(condition.rating()))
                .and(hasPhoto(condition.photoOnly()));

        List<Review> content = queryFactory
                .selectFrom(review)
                .where(where)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(review.count())
                .from(review)
                .where(where)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    @Override
    public List<Object[]> countGroupByRating(Long productId) {
        return queryFactory
                .select(review.rating, review.count())
                .from(review)
                .where(review.productId.eq(productId))
                .groupBy(review.rating)
                .fetch()
                .stream()
                .map(tuple -> new Object[]{tuple.get(review.rating), tuple.get(review.count())})
                .toList();
    }

    private BooleanExpression eqRating(Integer rating) {
        return rating != null ? review.rating.eq(rating) : null;
    }

    /** 사진리뷰만: imageUrl이 있고 빈 문자열이 아닌 것. (false·null이면 조건 없음 = 전체) */
    private BooleanExpression hasPhoto(Boolean photoOnly) {
        return Boolean.TRUE.equals(photoOnly)
                ? review.imageUrl.isNotNull().and(review.imageUrl.ne(""))
                : null;
    }

    /** Sort → OrderSpecifier. 정렬 키가 없으면 최신순. 항상 id desc를 tie-breaker로 덧붙인다. */
    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        PathBuilder<Review> path = new PathBuilder<>(Review.class, "review");
        for (Sort.Order o : sort) {
            Order direction = o.isAscending() ? Order.ASC : Order.DESC;
            @SuppressWarnings({"unchecked", "rawtypes"})
            OrderSpecifier<?> spec = new OrderSpecifier(direction, path.get(o.getProperty()));
            orders.add(spec);
        }
        if (orders.isEmpty()) {
            orders.add(review.createdAt.desc());
        }
        orders.add(review.id.desc());   // 동점 안정 정렬(페이지 경계 중복 방지)
        return orders.toArray(new OrderSpecifier[0]);
    }
}
