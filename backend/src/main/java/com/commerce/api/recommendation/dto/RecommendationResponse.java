package com.commerce.api.recommendation.dto;

import com.commerce.api.product.dto.ProductResponse;
import java.util.List;

/**
 * 추천 응답. 추천 상품 목록 + <b>personalized</b> 플래그.
 *
 * <p>personalized=true → 행동(구매·찜·조회) 기반 "나를 위한 추천", false → 이력이 없어 전체 인기순 폴백.
 * FE가 섹션 제목("나를 위한 추천" vs "인기 상품")을 이 플래그로 가른다.
 */
public record RecommendationResponse(
        boolean personalized,
        List<ProductResponse> products
) {
}
