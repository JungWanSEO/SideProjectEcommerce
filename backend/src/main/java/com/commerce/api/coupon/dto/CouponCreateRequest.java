package com.commerce.api.coupon.dto;

import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 쿠폰 발급 요청(ADMIN).
 *
 * <p>한 필드 안에서 닫히는 검증만 어노테이션으로(교차필드·유효기간 순서·정률 100% 같은 규칙은
 * {@code Coupon} 엔티티 생성자가 책임진다 — 도메인 불변식은 엔티티에).
 */
@Schema(description = "쿠폰 발급 요청")
public record CouponCreateRequest(

        @Schema(description = "쿠폰 코드(대문자로 정규화돼 저장)", example = "WELCOME5000")
        @NotBlank(message = "쿠폰 코드는 필수입니다.")
        @Size(max = 40, message = "쿠폰 코드는 40자 이내여야 합니다.")
        String code,

        @Schema(description = "표시용 이름", example = "신규가입 5천원 쿠폰")
        @NotBlank(message = "쿠폰 이름은 필수입니다.")
        @Size(max = 100, message = "쿠폰 이름은 100자 이내여야 합니다.")
        String name,

        @Schema(description = "할인 종류", example = "FIXED_AMOUNT")
        @NotNull(message = "할인 종류는 필수입니다.")
        DiscountType discountType,

        @Schema(description = "할인 값(정액=원, 정률=퍼센트 1~100)", example = "5000")
        @Positive(message = "할인 값은 0보다 커야 합니다.")
        long discountValue,

        @Schema(description = "정률 할인 상한(원, 선택). 정액/무제한이면 비움", example = "10000")
        @Positive(message = "할인 상한은 0보다 커야 합니다.")
        Long maxDiscountAmount,

        @Schema(description = "최소 적용 대상 금액(원)", example = "30000")
        @PositiveOrZero(message = "최소 주문금액은 0 이상이어야 합니다.")
        long minOrderAmount,

        @Schema(description = "할인 비용 부담 주체", example = "PLATFORM")
        @NotNull(message = "부담 주체는 필수입니다.")
        CouponFundedBy fundedBy,

        @Schema(description = "셀러 한정 시 셀러 ID(비우면 플랫폼 와이드=주문 전체 적용)", example = "1")
        Long sellerId,

        @Schema(description = "유효 시작 일시", example = "2026-06-01T00:00:00")
        @NotNull(message = "유효 시작 일시는 필수입니다.")
        LocalDateTime validFrom,

        @Schema(description = "유효 종료 일시", example = "2026-12-31T23:59:59")
        @NotNull(message = "유효 종료 일시는 필수입니다.")
        LocalDateTime validUntil
) {
}
