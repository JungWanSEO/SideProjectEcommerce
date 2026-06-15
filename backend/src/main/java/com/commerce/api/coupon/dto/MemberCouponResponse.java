package com.commerce.api.coupon.dto;

import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponStatus;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.entity.MemberCoupon;
import com.commerce.api.coupon.entity.MemberCouponStatus;
import java.time.LocalDateTime;

/**
 * 회원 쿠폰함의 한 장 — 발급된 쿠폰(MemberCoupon)에 쿠폰 상세를 enrich해 내려준다.
 *
 * <p>{@code usable} = 미사용 + 쿠폰 활성 + 기간 내. 체크아웃 지갑 드롭다운은 이 값으로 선택지를 거른다.
 */
public record MemberCouponResponse(
        Long id,                 // member_coupon id
        Long couponId,
        String code,
        String name,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        long minOrderAmount,
        CouponFundedBy fundedBy,
        Long sellerId,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        MemberCouponStatus status,
        LocalDateTime usedAt,
        boolean usable
) {
    public static MemberCouponResponse of(MemberCoupon mc, Coupon c, LocalDateTime now) {
        boolean usable = mc.isUnused()
                && c.getStatus() == CouponStatus.ACTIVE
                && !now.isBefore(c.getValidFrom()) && !now.isAfter(c.getValidUntil());
        return new MemberCouponResponse(
                mc.getId(), c.getId(), c.getCode(), c.getName(), c.getDiscountType(), c.getDiscountValue(),
                c.getMaxDiscountAmount(), c.getMinOrderAmount(), c.getFundedBy(), c.getSellerId(),
                c.getValidFrom(), c.getValidUntil(), mc.getStatus(), mc.getUsedAt(), usable);
    }
}
