package com.commerce.api.coupon.controller;

import com.commerce.api.coupon.dto.CouponCreateRequest;
import com.commerce.api.coupon.dto.CouponIssueRequest;
import com.commerce.api.coupon.dto.CouponResponse;
import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.coupon.service.CouponService;
import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final MemberCouponService memberCouponService;

    @Operation(summary = "쿠폰 생성", description = "정액/정률·플랫폼/셀러 부담·적용 범위(전체/셀러)·배포 방식(공개/발급)을 지정해 쿠폰을 만든다. 코드는 대문자로 정규화된다.")
    @Auditable(action = "COUPON_CREATE", targetType = "COUPON", targetId = "#result.body.data.id")
    @PostMapping
    public ResponseEntity<ApiResponse<CouponResponse>> create(
            @Valid @RequestBody CouponCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("쿠폰을 생성했습니다.", couponService.create(request)));
    }

    @Operation(summary = "쿠폰 발급(회원 지갑으로)",
            description = "발급형(ISSUED) 쿠폰을 전체 회원 또는 특정 이메일 회원에게 발급한다(쿠폰함). 이미 발급된 회원은 건너뛴다. 발급한 장수를 반환.")
    @Auditable(action = "COUPON_ISSUE", targetType = "COUPON", targetId = "#id")
    @PostMapping("/{id}/issue")
    public ResponseEntity<ApiResponse<Integer>> issue(
            @PathVariable Long id, @Valid @RequestBody CouponIssueRequest request) {
        int issued = memberCouponService.issue(id, request);
        return ResponseEntity.ok(ApiResponse.success(issued + "명에게 발급했습니다.", issued));
    }

    @Operation(summary = "쿠폰 목록", description = "발급된 쿠폰을 최신순으로 조회한다. 발급/사용 수·한도 포함.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CouponResponse>>> getCoupons() {
        return ResponseEntity.ok(ApiResponse.success(couponService.getCoupons()));
    }

    @Operation(summary = "쿠폰 중단(ADMIN)",
            description = "기간이 남아도 즉시 사용 불가로 만든다(할인율 오타 등으로 새는 쿠폰을 만료 전에 차단). "
                    + "없는 쿠폰이면 404. 이미 중단된 쿠폰이면 그대로.")
    @Auditable(action = "COUPON_DISABLE", targetType = "COUPON", targetId = "#id")
    @PatchMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<CouponResponse>> disable(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("쿠폰을 중단했습니다.", couponService.disable(id)));
    }
}
