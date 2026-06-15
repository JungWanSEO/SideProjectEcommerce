package com.commerce.api.wishlist.dto;

import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.wishlist.entity.Wishlist;
import java.time.LocalDateTime;

/**
 * 찜 항목 응답 DTO. 찜 한 줄(언제 찜했는지) + 그 상품의 표시 정보(product)를 함께 담는다.
 * 찜 목록 화면은 결국 "내가 찜한 상품들"을 상품 카드로 보여주므로 ProductResponse를 그대로 품는다.
 *
 * <p>product가 null일 수 있다 — 찜한 뒤 상품이 삭제된 경우(ID 참조라 FK로 막지 않음). FE가 방어한다.
 */
public record WishlistResponse(
        Long id,                  // wishlist 행 id (해제 시에는 productId를 쓰지만, 식별용으로 노출)
        Long productId,
        LocalDateTime wishlistedAt,
        ProductResponse product   // 찜한 상품 정보(enrich) — 삭제됐으면 null
) {
    public static WishlistResponse of(Wishlist wishlist, ProductResponse product) {
        return new WishlistResponse(
                wishlist.getId(),
                wishlist.getProductId(),
                wishlist.getCreatedAt(),
                product
        );
    }
}
