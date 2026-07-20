package com.commerce.api.product.repository;

import static com.commerce.api.product.entity.QProduct.product;

import com.commerce.api.brand.entity.QBrand;
import com.commerce.api.product.dto.LowStockOption;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.entity.QProductOption;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

/**
 * {@link ProductRepositoryCustom}의 QueryDSL 구현체.
 *
 * <p>{@code QProduct.product}는 빌드 시 애너테이션 프로세서가 Product 엔티티로부터 생성한
 * "쿼리 타입"이다. static import 해서 {@code product.name}, {@code product.price}처럼
 * 컬럼을 <b>타입 안전</b>하게 참조한다(오타는 컴파일 단계에서 잡힌다).
 */
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepositoryCustom {

    /** QuerydslConfig에서 등록한 빈을 생성자 주입(@RequiredArgsConstructor가 생성자 자동 생성). */
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Product> search(Collection<ProductStatus> visibleStatuses,
                                ProductSearchCondition condition,
                                Pageable pageable) {
        // 1) 동적 where 조립.
        //    BooleanBuilder.and(null)은 "그 조건을 무시"한다 → 값이 있을 때만 거는 조건을
        //    if 없이 깔끔하게 표현(아래 헬퍼들이 값 없으면 null 반환). LINQ의 조건부 Where와 같은 발상.
        BooleanBuilder where = new BooleanBuilder()
                .and(product.status.in(visibleStatuses))    // 가시 상태(판매중·품절)만 — 항상 적용
                .and(keywordContains(condition.keyword()))  // 키워드(있을 때만)
                .and(priceGoe(condition.minPrice()))        // 최소가(있을 때만)
                .and(priceLoe(condition.maxPrice()))        // 최대가(있을 때만)
                .and(eqCategory(condition.categoryId()))    // 카테고리(있을 때만)
                .and(eqBrand(condition.brandId()))          // 브랜드(있을 때만)
                .and(hasAvailableOption(condition.optionSize()));  // 사이즈(있을 때만)

        // 2) 콘텐츠 조회: where + 정렬 + 페이지(offset/limit)
        List<Product> content = queryFactory
                .selectFrom(product)
                .where(where)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // 3) 전체 개수: 페이지 메타(totalElements/totalPages) 계산용. 정렬·페이징 없이 count만.
        Long total = queryFactory
                .select(product.count())
                .from(product)
                .where(where)
                .fetchOne();

        // PageImpl로 묶으면 서비스에서 기존 PageResponse.from을 그대로 재사용할 수 있다.
        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    // --- 조건 헬퍼: 값이 없으면 null 반환 → 위 BooleanBuilder가 자동으로 무시 ---

    /**
     * 키워드 검색 — <b>상품명 · 브랜드명 · 설명</b>을 함께 훑는다(OR).
     *
     * <p>예전엔 상품명만 봐서 "MAISON"(브랜드)로 검색하면 0건이었다 — 쇼퍼가 브랜드로 찾는 건 아주 흔한데
     * 빈 화면을 만나 이탈했다.
     *
     * <p><b>브랜드는 조인이 아니라 서브쿼리</b>: {@code Product.brandId}는 객체 연관이 아니라 <b>ID 참조</b>
     * (애그리거트 경계 — DDD 결정)라 QueryDSL 조인 경로 자체가 없다. {@code brandId in (이름이 맞는 브랜드들)}로
     * 푼다. 덤으로 조인이 없어 <b>상품 행이 중복되지 않고</b>, 브랜드가 없는 상품(brandId=null)도 이름·설명 조건으로
     * 그대로 걸린다(잘못 leftJoin 했을 때 생기는 탈락 문제가 원천적으로 없다).
     *
     * <p>대소문자 무시: MySQL 기본 collation(utf8mb4_unicode_ci)은 이미 무시하지만 H2(테스트)는 다르다 →
     * {@code containsIgnoreCase}로 두 환경의 동작을 맞춘다. (어차피 LIKE %kw% 는 선행 와일드카드라 인덱스를
     * 못 타므로 LOWER() 추가 비용이 실질적으로 없다.)
     */
    private BooleanExpression keywordContains(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        QBrand brand = QBrand.brand;
        BooleanExpression brandNameMatches = product.brandId.in(
                JPAExpressions.select(brand.id)
                        .from(brand)
                        .where(brand.name.containsIgnoreCase(keyword)));

        return product.name.containsIgnoreCase(keyword)
                .or(brandNameMatches)
                .or(product.description.containsIgnoreCase(keyword));
    }

    private BooleanExpression priceGoe(Long minPrice) {
        return minPrice != null ? product.price.goe(minPrice) : null;                 // price >= min
    }

    private BooleanExpression priceLoe(Long maxPrice) {
        return maxPrice != null ? product.price.loe(maxPrice) : null;                 // price <= max
    }

    private BooleanExpression eqCategory(Long categoryId) {
        return categoryId != null ? product.categoryId.eq(categoryId) : null;         // category_id = ?
    }

    private BooleanExpression eqBrand(Long brandId) {
        return brandId != null ? product.brandId.eq(brandId) : null;                  // brand_id = ?
    }

    /**
     * 재고 임박·품절 옵션 목록. 상품이 아니라 <b>옵션 행</b>을 뽑으므로(사이즈별 재고) 여기선 exists가 아니라
     * option → product 조인이다. 재고 적은 순(품절이 맨 위) → 같은 재고면 상품·옵션 id로 안정 정렬.
     */
    @Override
    public List<LowStockOption> findLowStockOptions(Collection<ProductStatus> statuses, int threshold, int limit) {
        QProductOption option = QProductOption.productOption;
        return queryFactory
                .select(Projections.constructor(LowStockOption.class,
                        product.id, product.name, product.status, option.id, option.size, option.stock))
                .from(option)
                .join(option.product, product)
                .where(product.status.in(statuses), option.stock.loe(threshold))
                .orderBy(option.stock.asc(), product.id.asc(), option.id.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public long countOptionsWithStockBetween(Collection<ProductStatus> statuses, int min, int max) {
        QProductOption option = QProductOption.productOption;
        Long count = queryFactory
                .select(option.count())
                .from(option)
                .join(option.product, product)
                .where(product.status.in(statuses), option.stock.between(min, max))
                .fetchOne();
        return count == null ? 0L : count;
    }

    /**
     * 이 사이즈를 <b>구매 가능한(재고&gt;0)</b> 옵션으로 가진 상품만 — 옵션(SKU)에 대한 EXISTS 서브쿼리.
     * (옵션은 애그리거트 내부 컬렉션이라 상품 행은 중복되지 않게 join 대신 exists로 건다.)
     */
    private BooleanExpression hasAvailableOption(String optionSize) {
        if (!StringUtils.hasText(optionSize)) {
            return null;
        }
        QProductOption option = QProductOption.productOption;
        return JPAExpressions.selectOne()
                .from(option)
                .where(option.product.eq(product),
                        option.size.eq(optionSize),
                        option.stock.gt(0))
                .exists();
    }

    /**
     * Pageable의 정렬(Sort)을 QueryDSL의 {@link OrderSpecifier} 배열로 변환한다.
     *
     * <p>Pageable은 "price,asc" 같은 <b>문자열 기반</b> 정렬을 담고 있어, QueryDSL이 이해하는
     * 경로 객체로 바꿔줘야 한다. {@link PathBuilder}의 별칭("product")은 위에서 쓴
     * {@code QProduct.product}의 별칭과 같아야 올바른 쿼리(JPQL의 같은 별칭)가 생성된다.
     */
    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        PathBuilder<Product> path = new PathBuilder<>(Product.class, "product");
        for (Sort.Order o : sort) {
            Order direction = o.isAscending() ? Order.ASC : Order.DESC;

            // 평점 평균(ratingAverage)은 엔티티 컬럼이 아니라 계산값 → 별도 컬럼 없이 식으로 정렬한다.
            // 평균 = ratingSum/ratingCount, 리뷰가 없으면(count=0) -1로 두어 항상 맨 뒤로 보낸다.
            if ("ratingAverage".equals(o.getProperty())) {
                NumberExpression<Double> avg = new CaseBuilder()
                        .when(product.ratingCount.eq(0)).then(-1.0)
                        .otherwise(product.ratingSum.doubleValue().divide(product.ratingCount.doubleValue()));
                orders.add(new OrderSpecifier<>(direction, avg));
                continue;
            }

            // 그 외(createdAt·price·ratingCount 등 실제 컬럼)는 경로로 정렬.
            // 정렬 키가 런타임에 결정돼 제네릭 타입을 정적으로 알 수 없다 → raw 타입 사용(경고 억제).
            @SuppressWarnings({"unchecked", "rawtypes"})
            OrderSpecifier<?> spec = new OrderSpecifier(direction, path.get(o.getProperty()));
            orders.add(spec);
        }
        return orders.toArray(new OrderSpecifier[0]);
    }
}
