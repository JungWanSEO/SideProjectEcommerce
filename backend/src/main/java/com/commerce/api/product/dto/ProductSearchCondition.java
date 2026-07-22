package com.commerce.api.product.dto;

/**
 * 상품 검색/필터 조건.
 *
 * <p>모든 필드는 <b>선택적</b>이다 — null이면 그 조건은 적용하지 않는다.
 * 컨트롤러에서 쿼리 파라미터(`?keyword=..&minPrice=..&maxPrice=..&categoryId=..&brandId=..&optionSize=M`)로 바인딩된다.
 *
 * @param keyword    상품명 부분 일치(LIKE %keyword%)
 * @param minPrice   이 가격 이상(price &gt;= minPrice)
 * @param maxPrice   이 가격 이하(price &lt;= maxPrice)
 * @param categoryId 이 카테고리만
 * @param brandId    이 브랜드만
 * @param optionSize 이 사이즈를 구매 가능한(재고&gt;0) 옵션으로 가진 상품만.
 *                   <b>파라미터명이 {@code size}가 아니라 {@code optionSize}인 이유</b>: {@code size}는
 *                   Pageable의 페이지 크기 파라미터와 충돌하기 때문.
 * @param onSale     {@code true}면 <b>할인 중</b>(originalPrice &gt; price)인 상품만(SALE 탭·필터). null/false면 미적용.
 */
public record ProductSearchCondition(
        String keyword,
        Long minPrice,
        Long maxPrice,
        Long categoryId,
        Long brandId,
        String optionSize,
        Boolean onSale
) {
    /** onSale 없는 호출용(=null, SALE 필터 미적용). 기존 6-arg 호출부 호환. */
    public ProductSearchCondition(String keyword, Long minPrice, Long maxPrice,
            Long categoryId, Long brandId, String optionSize) {
        this(keyword, minPrice, maxPrice, categoryId, brandId, optionSize, null);
    }
}
