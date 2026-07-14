package com.commerce.api.review.dto;

/**
 * 리뷰 검색 조건 — 쿼리 파라미터로 바인딩(@ParameterObject). 없는 값은 조건에서 빠진다.
 *
 * @param rating    이 평점만(1~5). null이면 전체.
 * @param photoOnly true면 사진이 있는 리뷰만(사진리뷰는 신뢰도가 높아 따로 보려는 수요가 크다).
 */
public record ReviewSearchCondition(
        Integer rating,
        Boolean photoOnly
) {
}
