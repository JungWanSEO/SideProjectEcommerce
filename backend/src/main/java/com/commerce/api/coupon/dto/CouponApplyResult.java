package com.commerce.api.coupon.dto;

import com.commerce.api.coupon.entity.CouponFundedBy;

/**
 * 쿠폰 적용 결과 — 주문 도메인이 체크아웃에서 받아 주문에 스냅샷한다.
 *
 * <p>엔티티 대신 이 DTO로만 결과를 넘겨 도메인 경계를 지킨다(order → coupon은 service+DTO로만).
 * fundedBy/sellerId는 셀러별 정산 분담(Step 2)에서 쓰일 분담 메타다.
 *
 * @param code           적용된 쿠폰 코드(정규화된 값)
 * @param discountAmount 깎인 금액(원)
 * @param fundedBy       부담 주체(PLATFORM/SELLER) — 정산 분담(Step 2)
 * @param sellerId       셀러 한정 쿠폰이면 그 셀러 ID, 플랫폼 와이드면 null
 */
public record CouponApplyResult(
        String code,
        long discountAmount,
        CouponFundedBy fundedBy,
        Long sellerId
) {
}
