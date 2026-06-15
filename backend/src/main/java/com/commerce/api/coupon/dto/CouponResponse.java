package com.commerce.api.coupon.dto;

import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponIssueType;
import com.commerce.api.coupon.entity.CouponStatus;
import com.commerce.api.coupon.entity.DiscountType;
import java.time.LocalDateTime;

/**
 * 쿠폰 응답(ADMIN 조회). 엔티티를 그대로 노출하지 않고 필요한 필드만 추린다.
 */
public record CouponResponse(
        Long id,
        String code,
        String name,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        long minOrderAmount,
        CouponFundedBy fundedBy,
        CouponIssueType issueType,
        Long sellerId,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        CouponStatus status,
        LocalDateTime createdAt
) {
    public static CouponResponse from(Coupon c) {
        return new CouponResponse(
                c.getId(),
                c.getCode(),
                c.getName(),
                c.getDiscountType(),
                c.getDiscountValue(),
                c.getMaxDiscountAmount(),
                c.getMinOrderAmount(),
                c.getFundedBy(),
                c.getIssueType(),
                c.getSellerId(),
                c.getValidFrom(),
                c.getValidUntil(),
                c.getStatus(),
                c.getCreatedAt()
        );
    }
}
