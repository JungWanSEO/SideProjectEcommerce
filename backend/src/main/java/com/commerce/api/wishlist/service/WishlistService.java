package com.commerce.api.wishlist.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.config.CacheConfig;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import com.commerce.api.wishlist.dto.WishlistResponse;
import com.commerce.api.wishlist.entity.Wishlist;
import com.commerce.api.wishlist.repository.WishlistRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위시리스트(찜) 비즈니스 로직. 추가 / 해제 / 내 목록 / 내 찜 상품 ID.
 *
 * <p>핵심 정책:
 * <ul>
 *   <li><b>1인 1상품 1찜</b>: 이미 찜했으면 409(REST add 의미 — 멱등이 아니라 "이미 존재" 충돌).
 *   <li><b>인기도 카운터</b>: 추가/해제 시 Product.wishlistCount를 <b>원자 UPDATE</b>로 증감(리뷰 평점 카운터와
 *       동일 패턴 — 찜할 때마다 COUNT 하지 않고 상품에 누적, 인기순 정렬·추천 신호로 재사용).
 *   <li><b>IDOR 안전</b>: 모든 작업은 인증된 memberId 기준으로만 — 남의 찜을 건드릴 표면이 없다.
 * </ul>
 *
 * <p>상품 정보 enrich(이름·이미지 등)는 {@link ProductService#getProductMap}에 위임한다(상품 enrich 로직 단일화).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    /** 찜 추가: 상품 존재(404) → 중복(409) 검증 → 저장 + 상품 찜 카운터 증가. */
    // 찜 카운터(wishlistCount)가 바뀌므로 그 상품 상세 캐시를 무효화.
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#productId")
    @Transactional
    public WishlistResponse add(Long memberId, Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        if (wishlistRepository.existsByMemberIdAndProductId(memberId, productId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 찜한 상품입니다.");
        }

        Wishlist saved = wishlistRepository.save(Wishlist.builder()
                .memberId(memberId)
                .productId(productId)
                .build());
        productRepository.incrementWishlist(productId);   // 인기도 카운터 +1 (원자 UPDATE)

        // 방금 저장한 상품 1건의 enrich 정보를 채워 응답(목록과 같은 형태 유지).
        ProductResponse product = productService.getProductMap(List.of(productId)).get(productId);
        return WishlistResponse.of(saved, product);
    }

    /** 찜 해제: 내 찜 행을 찾아(없으면 404) 삭제 + 상품 찜 카운터 감소. */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#productId")
    @Transactional
    public void remove(Long memberId, Long productId) {
        Wishlist wishlist = wishlistRepository.findByMemberIdAndProductId(memberId, productId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "찜하지 않은 상품입니다."));
        wishlistRepository.delete(wishlist);
        productRepository.decrementWishlist(productId);   // 인기도 카운터 -1 (0 가드는 쿼리에서)
    }

    /** 내 찜 목록(페이지). 찜 행들의 productId를 모아 상품 정보를 한 번에 enrich(N+1 회피). */
    public PageResponse<WishlistResponse> getMyWishlist(Long memberId, Pageable pageable) {
        Page<Wishlist> page = wishlistRepository.findByMemberId(memberId, pageable);
        List<Long> productIds = page.getContent().stream().map(Wishlist::getProductId).toList();
        Map<Long, ProductResponse> products = productService.getProductMap(productIds);
        // Map.get(없는 id)=null → 삭제된 상품도 안전(WishlistResponse.product=null).
        return PageResponse.from(page.map(w -> WishlistResponse.of(w, products.get(w.getProductId()))));
    }

    /** 내가 찜한 상품 ID 전체 — FE가 상품 카드/상세에서 하트 채움 여부를 한 번에 판단하도록. */
    public List<Long> getMyProductIds(Long memberId) {
        return wishlistRepository.findProductIdsByMemberId(memberId);
    }
}
