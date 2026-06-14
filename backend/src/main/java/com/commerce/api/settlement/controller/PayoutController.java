package com.commerce.api.settlement.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.settlement.dto.PayoutCreateRequest;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.service.PayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지급 묶음(Payout) API — 전부 ADMIN 전용(SecurityConfig: /api/payouts/** hasRole("ADMIN")).
 *
 * - POST /api/payouts          셀러+기간으로 지급 묶음 생성(SCHEDULED·미지급 항목 묶기)
 * - POST /api/payouts/{id}/pay 지급 완료(묶인 항목들 PAID_OUT)
 * - GET  /api/payouts          묶음 목록(셀러·상태 필터)
 */
@Tag(name = "지급 묶음(Payout)", description = "셀러 지급 묶음 생성/지급/조회 API (ADMIN)")
@RestController
@RequestMapping("/api/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    @Operation(summary = "지급 묶음 생성(ADMIN)",
            description = "셀러의 SCHEDULED·미지급 정산 항목을 정산일 기간으로 묶는다. 대상 없으면 400.")
    @PostMapping
    public ResponseEntity<ApiResponse<PayoutResponse>> create(@Valid @RequestBody PayoutCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("지급 묶음을 생성했습니다.", payoutService.create(request)));
    }

    @Operation(summary = "지급 완료(ADMIN)", description = "묶음을 지급 완료로 처리하고 묶인 항목을 PAID_OUT으로. 이미 지급이면 409.")
    @PostMapping("/{id}/pay")
    public ResponseEntity<ApiResponse<PayoutResponse>> pay(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("지급 완료로 처리했습니다.", payoutService.pay(id)));
    }

    @Operation(summary = "지급 묶음 목록(ADMIN)", description = "셀러/상태 필터(선택). 최신순.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PayoutResponse>>> getPayouts(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) PayoutStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(payoutService.getPayouts(sellerId, status, pageable)));
    }
}
