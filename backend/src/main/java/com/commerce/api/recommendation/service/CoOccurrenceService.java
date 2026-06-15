package com.commerce.api.recommendation.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import com.commerce.api.recommendation.dto.CoOccurrenceResponse;
import com.commerce.api.recommendation.entity.ProductCoOccurrence;
import com.commerce.api.recommendation.repository.ProductCoOccurrenceRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 함께 산 상품 조회. 배치가 미리 계산해 둔 결과 테이블을 정렬 조회만 한다(매 요청 계산 X).
 *
 * <p><b>콜드스타트 폴백</b>: 함께 산 데이터가 없으면(신상품·배치 전·단일 항목 주문뿐) 빈 화면 대신
 * <b>같은 카테고리/브랜드</b>의 인기 상품으로, 그마저 없으면 전체 인기순으로 채운다.
 * cooccurrence 플래그로 "함께 산 상품"인지 "비슷한 상품(폴백)"인지 구분해 응답한다.
 * 상품 enrich(이름·이미지 등)는 {@link ProductService#getProductMap}에 위임(단일화).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoOccurrenceService {

    private final ProductCoOccurrenceRepository coOccurrenceRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    /** 기준 상품의 함께 산 상품. 저장된 결과가 있으면 그것(cooccurrence=true), 없으면 카테고리/브랜드·인기순 폴백. */
    public CoOccurrenceResponse getCoOccurrence(Long productId, int limit) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."));

        List<ProductCoOccurrence> rows = coOccurrenceRepository
                .findByReferenceProductIdOrderByScoreDescProductIdAsc(productId, PageRequest.of(0, limit));
        if (!rows.isEmpty()) {
            List<ProductResponse> products = enrich(rows.stream().map(ProductCoOccurrence::getProductId).toList());
            if (!products.isEmpty()) {
                return new CoOccurrenceResponse(true, products);
            }
        }
        return new CoOccurrenceResponse(false, fallback(product, limit));
    }

    /** 폴백: 같은 카테고리/브랜드 ON_SALE 인기순(기준 상품 제외). 그마저 비면 전체 인기순으로 한 번 더. */
    private List<ProductResponse> fallback(Product product, int limit) {
        List<Long> ids = productRepository.findCoOccurrenceFallback(
                        product.getCategoryId(), product.getBrandId(), product.getId(),
                        ProductStatus.ON_SALE, PageRequest.of(0, limit))
                .stream().map(Product::getId).toList();
        if (ids.isEmpty()) {
            ids = productRepository
                    .findTop12ByStatusOrderByWishlistCountDescRatingCountDesc(ProductStatus.ON_SALE).stream()
                    .map(Product::getId)
                    .filter(id -> !id.equals(product.getId()))   // 기준 상품 자신 제외
                    .limit(limit)
                    .toList();
        }
        return enrich(ids);
    }

    /** productId 묶음 → 상품 응답(원래 순서 유지, 삭제된 상품은 제외). */
    private List<ProductResponse> enrich(List<Long> productIds) {
        Map<Long, ProductResponse> map = productService.getProductMap(productIds);
        return productIds.stream().map(map::get).filter(Objects::nonNull).toList();
    }
}
