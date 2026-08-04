package com.commerce.api.returns.controller;

import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.service.ReturnQueryService;
import com.commerce.api.returns.service.ReturnService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 반품/교환 API — 구매자(#3).
 * - POST /api/orders/{orderId}/returns  반품/교환 요청
 * - GET  /api/returns/me                내 반품 목록
 */
@Tag(name = "반품/교환(Return)", description = "구매자 반품·교환 요청 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnService returnService;
    private final ReturnQueryService returnQueryService;

    @Operation(summary = "반품/교환 요청",
            description = "배송 완료된 주문 항목을 반품(환불) 또는 교환한다. 본인 주문만, 배송완료 후 7일 이내, ACTIVE 항목만. "
                    + "교환이면 대체 옵션(exchangeOptionId) 필수. 이미 진행 중인 반품이 있으면 409.")
    @Auditable(action = "RETURN_REQUEST", targetType = "ORDER", targetId = "#orderId")
    @PostMapping("/orders/{orderId}/returns")
    public ResponseEntity<ApiResponse<ReturnResponse>> create(
            @PathVariable Long orderId, @Valid @RequestBody ReturnCreateRequest request) {
        ReturnResponse response = returnService.create(
                SecurityUtil.getCurrentMemberId(), SecurityUtil.isAdmin(), orderId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("반품/교환이 접수되었습니다.", response));
    }

    @Operation(summary = "내 반품/교환 목록", description = "로그인 구매자 본인의 반품·교환 요청 목록(최신순).")
    @GetMapping("/returns/me")
    public ResponseEntity<ApiResponse<PageResponse<ReturnResponse>>> getMyReturns(
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                returnQueryService.getMyReturns(SecurityUtil.getCurrentMemberId(), pageable)));
    }

    @Operation(summary = "반품/교환 전체 검색(ADMIN)",
            description = "운영자용 전체 반품·교환 목록. status·type·sellerId로 필터(생략 시 전체), 최신순. "
                    + "대행 처리는 PATCH /api/orders/{orderId}/returns/{returnId}/status. "
                    + "구매자/셀러 목록과 달리 소유로 좁히지 않으므로 경로 인가(ADMIN)가 유일한 방어선이다.")
    @GetMapping("/returns/admin")
    public ResponseEntity<ApiResponse<PageResponse<ReturnResponse>>> searchForAdmin(
            @RequestParam(required = false) ReturnStatus status,
            @RequestParam(required = false) ReturnType type,
            @RequestParam(required = false) Long sellerId,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                returnQueryService.searchForAdmin(status, type, sellerId, pageable)));
    }
}
