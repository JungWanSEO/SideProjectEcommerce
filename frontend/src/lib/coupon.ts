import { Coupon, CouponFundedBy, CouponIssueType, DiscountType } from "./types";

/** 할인 종류 라벨 */
export const DISCOUNT_TYPE_LABEL: Record<DiscountType, string> = {
  FIXED_AMOUNT: "정액",
  PERCENTAGE: "정률",
};

/** 할인 부담 주체 라벨 (셀러별 정산 분담 축 — 쿠폰 Step 2) */
export const FUNDED_BY_LABEL: Record<CouponFundedBy, string> = {
  PLATFORM: "플랫폼 부담",
  SELLER: "셀러 부담",
};

/** 부담 주체 뱃지 색 (어드민 그레이 톤) */
export const FUNDED_BY_BADGE: Record<CouponFundedBy, string> = {
  PLATFORM: "bg-indigo-50 text-indigo-700",
  SELLER: "bg-amber-50 text-amber-700",
};

/** 배포 방식 라벨/뱃지 (공개 코드 / 회원 발급) — Step 3 */
export const ISSUE_TYPE_LABEL: Record<CouponIssueType, string> = {
  PUBLIC: "공개 코드",
  ISSUED: "회원 발급",
};

export const ISSUE_TYPE_BADGE: Record<CouponIssueType, string> = {
  PUBLIC: "bg-gray-100 text-gray-600",
  ISSUED: "bg-violet-50 text-violet-700",
};

/** 쿠폰 상태 라벨/뱃지 (운영 스위치 ACTIVE/DISABLED + 기간) */
export const COUPON_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "활성",
  DISABLED: "비활성",
  EXPIRED: "기간 외",
};

export const COUPON_STATUS_BADGE: Record<string, string> = {
  ACTIVE: "bg-green-100 text-green-700",
  DISABLED: "bg-gray-100 text-gray-600",
  EXPIRED: "bg-amber-100 text-amber-700",
};

/** 할인 표시(필드 직접) — 정액은 "5,000원", 정률은 "10% (최대 1만원)". Coupon·MemberCoupon 공용. */
export function formatDiscountOf(
  discountType: DiscountType,
  discountValue: number,
  maxDiscountAmount: number | null,
): string {
  if (discountType === "FIXED_AMOUNT") {
    return `${discountValue.toLocaleString()}원`;
  }
  const cap = maxDiscountAmount ? ` (최대 ${maxDiscountAmount.toLocaleString()}원)` : "";
  return `${discountValue}%${cap}`;
}

/** 할인 표시 — 쿠폰. */
export function formatDiscount(c: Coupon): string {
  return formatDiscountOf(c.discountType, c.discountValue, c.maxDiscountAmount);
}

/** 적용 범위 표시 — 셀러 한정이면 셀러 ID, 아니면 전체. */
export function scopeLabel(c: Coupon): string {
  return c.sellerId == null ? "전체(플랫폼)" : `셀러 #${c.sellerId}`;
}

/** 운영 상태 + 기간으로 실질 상태를 도출. now는 호출자가 넘긴다(SSR-안전). */
export function effectiveStatus(c: Coupon, now: Date): "ACTIVE" | "DISABLED" | "EXPIRED" {
  if (c.status === "DISABLED") return "DISABLED";
  const from = new Date(c.validFrom);
  const until = new Date(c.validUntil);
  if (now < from || now > until) return "EXPIRED";
  return "ACTIVE";
}
