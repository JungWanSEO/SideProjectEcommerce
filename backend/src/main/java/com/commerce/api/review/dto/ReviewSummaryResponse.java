package com.commerce.api.review.dto;

import java.util.List;

/**
 * 리뷰 평점 요약 — 별점 분포(5~1) + 총 개수 + 평균.
 *
 * <p>분포는 <b>항상 5개 행(5★~1★)</b>을 준다(없는 별점은 0) — 그래야 화면의 막대 그래프가 늘 같은 모양이다.
 * 평균은 분포에서 계산해(Σ rating×count / total) 목록·분포와 항상 일관된다.
 */
public record ReviewSummaryResponse(
        long total,
        double average,                  // 소수 1자리
        List<RatingCount> distribution   // 5★ → 1★ 순
) {
    public record RatingCount(int rating, long count) {}
}
