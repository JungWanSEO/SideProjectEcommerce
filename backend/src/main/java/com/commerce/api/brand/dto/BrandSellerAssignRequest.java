package com.commerce.api.brand.dto;

/**
 * 브랜드를 셀러에 귀속시키는 요청(ADMIN).
 *
 * <p>sellerId가 null이면 귀속 해제(미지정 = 플랫폼 직매입/미귀속). null이 아니면
 * 해당 셀러가 존재해야 한다(없으면 400).
 */
public record BrandSellerAssignRequest(Long sellerId) {
}
