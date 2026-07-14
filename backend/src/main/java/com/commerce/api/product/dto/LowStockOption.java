package com.commerce.api.product.dto;

import com.commerce.api.product.entity.ProductStatus;

/**
 * 재고가 임계치 이하인 상품 옵션 1건 (재고 임박·품절 리포트).
 *
 * <p>재고는 상품이 아니라 <b>옵션(사이즈=SKU)</b>에 있으므로 리포트의 단위도 옵션이다
 * (판매중 상품이어도 M 사이즈만 품절일 수 있다 — 그게 이 리포트의 존재 이유).
 */
public record LowStockOption(
        Long productId,
        String productName,
        ProductStatus productStatus,
        Long optionId,
        String size,
        int stock            // 0이면 품절
) {
}
