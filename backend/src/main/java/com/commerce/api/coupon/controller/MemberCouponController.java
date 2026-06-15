package com.commerce.api.coupon.controller;

import com.commerce.api.coupon.dto.MemberCouponResponse;
import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.security.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 쿠폰함 API — 로그인 사용자 본인의 발급 쿠폰만 조회한다(authenticated, 본인 스코핑).
 * 발급은 ADMIN(POST /api/coupons/{id}/issue), 적용은 체크아웃에서 자동 처리.
 * - GET /api/member-coupons/me   내 쿠폰함(최신순, usable 포함)
 */
@Tag(name = "회원 쿠폰함(MemberCoupon)", description = "내 쿠폰함 조회 API")
@RestController
@RequestMapping("/api/member-coupons")
@RequiredArgsConstructor
public class MemberCouponController {

    private final MemberCouponService memberCouponService;

    @Operation(summary = "내 쿠폰함", description = "로그인 사용자가 발급받은 쿠폰을 최신순으로 조회한다. usable=사용 가능(미사용+활성+기간 내).")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<MemberCouponResponse>>> getMyWallet() {
        return ResponseEntity.ok(ApiResponse.success(
                memberCouponService.getMyWallet(SecurityUtil.getCurrentMemberId())));
    }
}
