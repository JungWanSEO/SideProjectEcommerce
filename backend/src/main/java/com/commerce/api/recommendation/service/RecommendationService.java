package com.commerce.api.recommendation.service;

import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import com.commerce.api.recommendation.dto.RecommendationResponse;
import com.commerce.api.recommendation.entity.Recommendation;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 조회. 배치가 미리 계산해 둔 결과 테이블을 정렬 조회만 한다(매 요청 계산 X).
 *
 * <p><b>콜드스타트 폴백</b>: 저장된 추천이 없으면(이력 없는 회원·배치 전) <b>전체 인기순</b>으로 채워
 * 빈 화면을 피한다. personalized 플래그로 "나를 위한 추천"인지 "인기 상품"인지 구분해 응답한다.
 * 상품 enrich(이름·이미지 등)는 {@link ProductService#getProductMap}에 위임(단일화).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private final RecommendationRepository recommendationRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    /** 내 추천. 저장된 추천이 있으면 그것(personalized), 없으면 인기순 폴백. */
    public RecommendationResponse getMyRecommendations(Long memberId) {
        List<Recommendation> recs = recommendationRepository.findByMemberIdOrderByScoreDescProductIdAsc(memberId);
        if (!recs.isEmpty()) {
            List<ProductResponse> products = enrich(recs.stream().map(Recommendation::getProductId).toList());
            if (!products.isEmpty()) {
                return new RecommendationResponse(true, products);
            }
        }
        return new RecommendationResponse(false, popularFallback());
    }

    /** 콜드스타트: 전체 인기순(찜 수→리뷰 수) ON_SALE 상위 12개. */
    private List<ProductResponse> popularFallback() {
        List<Long> ids = productRepository
                .findTop12ByStatusOrderByWishlistCountDescRatingCountDesc(ProductStatus.ON_SALE)
                .stream().map(Product::getId).toList();
        return enrich(ids);
    }

    /** productId 묶음 → 상품 응답(원래 순서 유지, 삭제된 상품은 제외). */
    private List<ProductResponse> enrich(List<Long> productIds) {
        Map<Long, ProductResponse> map = productService.getProductMap(productIds);
        return productIds.stream().map(map::get).filter(Objects::nonNull).toList();
    }
}
