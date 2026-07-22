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
        LocalDateTime createdAt,
        Integer totalQuantity,      // 선착순 한도(장). null = 무제한
        Integer remainingQuantity,  // 남은 발급 수(totalQuantity - issuedCount). 무제한이면 null
        int issuedCount,            // 발급된 수(선착순 claim·지갑 발급 누계). PUBLIC 무제한 쿠폰은 0
        long usedCount              // 사용된 수(member_coupon status=USED). 발급형(ISSUED) 쿠폰에서 유의미
) {
    /** 사용 수 없이(생성 직후 등) — usedCount=0. */
    public static CouponResponse from(Coupon c) {
        return from(c, 0L);
    }

    public static CouponResponse from(Coupon c, long usedCount) {
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
                c.getCreatedAt(),
                c.getTotalQuantity(),
                remainingOf(c),
                c.getIssuedCount(),
                usedCount
        );
    }

    /** 남은 발급 수 — 무제한(totalQuantity=null)이면 null, 한정이면 0 이상(소진 시 0). */
    static Integer remainingOf(Coupon c) {
        if (c.getTotalQuantity() == null) {
            return null;   // 무제한
        }
        return Math.max(0, c.getTotalQuantity() - c.getIssuedCount());
    }
}
