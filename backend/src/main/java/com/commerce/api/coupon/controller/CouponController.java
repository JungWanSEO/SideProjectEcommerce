package com.commerce.api.coupon.controller;

import com.commerce.api.coupon.dto.CouponCreateRequest;
import com.commerce.api.coupon.dto.CouponResponse;
import com.commerce.api.coupon.service.CouponService;
import com.commerce.api.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿠폰 관리 API — 운영 업무라 전 경로 ADMIN 전용(SecurityConfig에서 hasRole ADMIN).
 * 고객은 별도 조회 없이 체크아웃에서 코드를 입력해 적용한다(POST /api/orders/checkout).
 * - POST /api/coupons   쿠폰 발급
 * - GET  /api/coupons   쿠폰 목록(최신순)
 */
@Tag(name = "쿠폰(Coupon)", description = "쿠폰 발급 / 조회 API (ADMIN)")
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "쿠폰 발급", description = "정액/정률·플랫폼/셀러 부담·적용 범위(전체/셀러)를 지정해 쿠폰을 발급한다. 코드는 대문자로 정규화된다.")
    @PostMapping
    public ResponseEntity<ApiResponse<CouponResponse>> create(
            @Valid @RequestBody CouponCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("쿠폰을 발급했습니다.", couponService.create(request)));
    }

    @Operation(summary = "쿠폰 목록", description = "발급된 쿠폰을 최신순으로 조회한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CouponResponse>>> getCoupons() {
        return ResponseEntity.ok(ApiResponse.success(couponService.getCoupons()));
    }
}
