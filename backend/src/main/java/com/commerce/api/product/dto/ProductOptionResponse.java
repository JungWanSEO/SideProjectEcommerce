package com.commerce.api.product.dto;

import com.commerce.api.product.entity.ProductOption;

/**
 * 상품 옵션(사이즈) 응답.
 *
 * <p>{@code stock}=물리 재고(어드민 재고 관리용), {@code available}=가용재고(stock−reserved, 지금 살 수 있는 수량),
 * {@code soldOut}=가용재고 0(품절 표시). 예약(#2)이 잡힌 만큼 available &lt; stock 이 된다.
 */
public record ProductOptionResponse(Long id, String size, int stock, int available, boolean soldOut) {

    /** available 없는 호출용(예약 0 가정 → available=stock). 기존 4-arg 호출부 호환. */
    public ProductOptionResponse(Long id, String size, int stock, boolean soldOut) {
        this(id, size, stock, stock, soldOut);
    }

    public static ProductOptionResponse from(ProductOption option) {
        return new ProductOptionResponse(
                option.getId(), option.getSize(), option.getStock(), option.available(), option.isSoldOut());
    }
}
