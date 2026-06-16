package com.commerce.api.product.dto;

import com.commerce.api.product.entity.ProductImage;

/**
 * 상품 이미지(갤러리) 응답. 관리자 삭제용 id + 노출용 url/순서.
 */
public record ProductImageResponse(Long id, String url, int sortOrder) {

    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(image.getId(), image.getUrl(), image.getSortOrder());
    }
}
