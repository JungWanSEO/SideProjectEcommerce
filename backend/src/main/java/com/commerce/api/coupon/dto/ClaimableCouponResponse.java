package com.commerce.api.coupon.dto;

import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.DiscountType;
import java.time.LocalDateTime;

/**
 * 회원이 직접 받을 수 있는(claimable) 선착순 쿠폰 한 장.
 *
 * <p>{@link CouponResponse}(ADMIN 관리용)와 달리 <b>회원 관점</b>이다 — 같은 쿠폰이라도 회원마다 달라지는
 * {@code alreadyClaimed}(이미 받았는지)를 담는다. 화면은 잔여수량/마감/이미받음으로 "받기" 버튼 상태를 정한다.
 *
 * <p>대상은 {@code issueType=ISSUED} + {@code status=ACTIVE} + 발급기간 내 쿠폰(= claim 가능한 것들).
 */
public record ClaimableCouponResponse(
        Long id,                    // coupon id (claim 경로의 {couponId})
        String code,
        String name,
        DiscountType discountType,
        long discountValue,
        Long maxDiscountAmount,
        long minOrderAmount,
        Long sellerId,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        Integer totalQuantity,      // 선착순 한도(장). null = 무제한
        Integer remainingQuantity,  // 남은 수(무제한이면 null, 소진 시 0)
        boolean soldOut,            // 한정 쿠폰이 모두 소진됨(무제한은 항상 false)
        boolean alreadyClaimed      // 이 회원이 이미 받았는지(회원·쿠폰당 1장)
) {
    /** 쿠폰 + "이 회원이 이미 받았는지"로 회원 관점 응답을 만든다. 잔여수량 계산은 CouponResponse와 공유. */
    public static ClaimableCouponResponse of(Coupon c, boolean alreadyClaimed) {
        Integer remaining = CouponResponse.remainingOf(c);   // 무제한이면 null
        boolean soldOut = remaining != null && remaining == 0;
        return new ClaimableCouponResponse(
                c.getId(), c.getCode(), c.getName(), c.getDiscountType(), c.getDiscountValue(),
                c.getMaxDiscountAmount(), c.getMinOrderAmount(), c.getSellerId(),
                c.getValidFrom(), c.getValidUntil(),
                c.getTotalQuantity(), remaining, soldOut, alreadyClaimed);
    }
}
