package com.commerce.api.recommendation.dto;

import com.commerce.api.product.dto.ProductResponse;
import java.util.List;

/**
 * 함께 산 상품 응답. 추천 상품 목록 + <b>cooccurrence</b> 플래그.
 *
 * <p>cooccurrence=true → 실제 "함께 산" 주문 통계 기반, false → 데이터가 없어 같은 카테고리/브랜드(또는 인기순)로 폴백.
 * FE가 섹션 문구("함께 산 상품" vs "비슷한 상품")를 이 플래그로 가를 수 있다.
 */
public record CoOccurrenceResponse(
        boolean cooccurrence,
        List<ProductResponse> products
) {
}
