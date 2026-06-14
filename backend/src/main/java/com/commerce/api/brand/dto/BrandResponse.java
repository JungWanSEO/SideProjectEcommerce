package com.commerce.api.brand.dto;

import com.commerce.api.brand.entity.Brand;

/**
 * 브랜드 응답.
 */
public record BrandResponse(Long id, String name, Long sellerId) {

    public static BrandResponse from(Brand brand) {
        return new BrandResponse(brand.getId(), brand.getName(), brand.getSellerId());
    }
}
