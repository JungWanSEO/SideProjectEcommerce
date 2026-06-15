package com.commerce.api.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

/**
 * 쿠폰 발급 요청(ADMIN) — 발급형(ISSUED) 쿠폰을 회원에게 발급한다.
 * {@code toAll}이면 전체 회원, 아니면 {@code email}의 회원 한 명에게.
 */
@Schema(description = "쿠폰 발급 요청 (전체 회원 또는 특정 이메일)")
public record CouponIssueRequest(

        @Schema(description = "전체 회원에게 발급할지", example = "false")
        boolean toAll,

        @Schema(description = "특정 회원 이메일(toAll=false일 때 필수)", example = "buyer@commerce.com")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email
) {
}
