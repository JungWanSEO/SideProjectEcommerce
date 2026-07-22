package com.commerce.api.product.dto;

import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 상품 응답 DTO. 엔티티 대신 필요한 필드만 노출.
 *
 * <p>재고는 상품이 아니라 옵션(사이즈)에 있으므로 stock 대신 <b>options 목록</b>(사이즈별 재고·품절)을 담는다.
 * 카테고리·브랜드 이름은 서비스가 enrich해서 넘긴다. <b>imageUrl</b>은 대표 1장, <b>images</b>는 갤러리(추가 이미지).
 */
public record ProductResponse(
        Long id,
        String name,
        long price,             // 판매가(결제 기준)
        Long originalPrice,     // 정가(취소선). null=비할인. originalPrice>price일 때만 할인, 할인율은 FE가 계산. 결제는 price 기준.
        String description,
        String imageUrl,        // 대표 이미지 URL (없으면 null → FE가 placeholder 폴백)
        ProductStatus status,
        Long categoryId,
        String categoryName,
        Long brandId,
        String brandName,
        List<ProductOptionResponse> options,   // 사이즈별 재고/품절
        int ratingCount,        // 리뷰 수
        double ratingAverage,   // 평점 평균(소수 1자리, 리뷰 없으면 0)
        int wishlistCount,      // 찜 수(인기도 신호)
        LocalDateTime createdAt,
        List<ProductImageResponse> images      // 갤러리(대표 imageUrl 외 추가 이미지들, sortOrder 순)
) {
    /** 갤러리·정가 없는 호출용 편의 생성자(images=빈 목록, originalPrice=null). 기존 15-arg 호출부 호환. */
    public ProductResponse(Long id, String name, long price, String description, String imageUrl,
            ProductStatus status, Long categoryId, String categoryName, Long brandId, String brandName,
            List<ProductOptionResponse> options, int ratingCount, double ratingAverage, int wishlistCount,
            LocalDateTime createdAt) {
        this(id, name, price, null, description, imageUrl, status, categoryId, categoryName, brandId, brandName,
                options, ratingCount, ratingAverage, wishlistCount, createdAt, List.of());
    }

    public static ProductResponse of(Product product, String categoryName, String brandName) {
        List<ProductOptionResponse> options = product.getOptions().stream()
                .map(ProductOptionResponse::from)
                .toList();
        List<ProductImageResponse> images = product.getImages().stream()
                .map(ProductImageResponse::from)
                .toList();
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getDescription(),
                product.getImageUrl(),
                product.getStatus(),
                product.getCategoryId(),
                categoryName,
                product.getBrandId(),
                brandName,
                options,
                product.getRatingCount(),
                product.getRatingAverage(),
                product.getWishlistCount(),
                product.getCreatedAt(),
                images
        );
    }
}
