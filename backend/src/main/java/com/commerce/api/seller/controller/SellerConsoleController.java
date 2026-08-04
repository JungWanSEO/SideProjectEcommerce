package com.commerce.api.seller.controller;

import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.notification.dto.NotificationResponse;
import com.commerce.api.order.dto.OrderSearchCondition;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.dto.SellerShipmentResponse;
import com.commerce.api.order.dto.ShipmentStatusUpdateRequest;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.service.SellerConsoleService;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.entity.SettlementStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 콘솔 API — 로그인한 셀러 본인 전용(SecurityConfig: /api/seller/** hasRole("SELLER")).
 * 셀러는 자기 sellerId로만 스코핑되어 남의 정산은 볼 수 없다. (ADMIN 전체 조회는 /api/settlements 별도.)
 *
 * - GET /api/seller/me              내 셀러 정보
 * - GET /api/seller/me/settlements  내 정산 항목(상태·기간 필터)
 * - GET /api/seller/me/summary      내 정산서(셀러별 집계)
 */
@Tag(name = "셀러 콘솔(Seller Console)", description = "로그인 셀러 본인 정산 조회 API (SELLER)")
@RestController
@RequestMapping("/api/seller")
@RequiredArgsConstructor
public class SellerConsoleController {

    private final SellerConsoleService sellerConsoleService;

    @Operation(summary = "내 셀러 정보", description = "로그인 셀러 본인의 셀러 정보. 셀러 계정이 아니면 403.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SellerResponse>> getMySeller() {
        return ResponseEntity.ok(
                ApiResponse.success(sellerConsoleService.getMySeller(SecurityUtil.getCurrentMemberId())));
    }

    @Operation(summary = "내 알림 목록", description = "셀러 인박스(새 주문·반품요청 등)를 최신순으로. unreadOnly=true면 안읽음만. (#6)")
    @GetMapping("/me/notifications")
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> getMyNotifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(sellerConsoleService.getMyNotifications(
                SecurityUtil.getCurrentMemberId(), unreadOnly, pageable)));
    }

    @Operation(summary = "내 안읽음 알림 수", description = "셀러 콘솔 벨 뱃지용.")
    @GetMapping("/me/notifications/unread-count")
    public ResponseEntity<ApiResponse<Long>> myUnreadNotificationCount() {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.myUnreadNotificationCount(SecurityUtil.getCurrentMemberId())));
    }

    @Operation(summary = "내 알림 읽음 처리", description = "본인 셀러 알림만. 없거나 남의 것이면 404.")
    @PatchMapping("/me/notifications/{id}/read")
    public ResponseEntity<ApiResponse<Void>> markNotificationRead(@PathVariable Long id) {
        sellerConsoleService.markMyNotificationRead(SecurityUtil.getCurrentMemberId(), id);
        return ResponseEntity.ok(ApiResponse.success("읽음 처리되었습니다.", null));
    }

    @Operation(summary = "내 알림 전체 읽음", description = "안읽음 알림을 모두 읽음으로. 처리 건수 반환.")
    @PatchMapping("/me/notifications/read-all")
    public ResponseEntity<ApiResponse<Integer>> markAllNotificationsRead() {
        int marked = sellerConsoleService.markAllMyNotificationsRead(SecurityUtil.getCurrentMemberId());
        return ResponseEntity.ok(ApiResponse.success("모두 읽음 처리되었습니다.", marked));
    }

    @Operation(summary = "내 정산 항목", description = "본인 셀러의 정산 항목(상태·기간 필터). 최신순.")
    @GetMapping("/me/settlements")
    public ResponseEntity<ApiResponse<PageResponse<SettlementResponse>>> getMySettlements(
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMySettlements(
                        SecurityUtil.getCurrentMemberId(), status, from, to, pageable)));
    }

    @Operation(summary = "내 정산서", description = "본인 셀러의 매출·PG수수료·플랫폼수수료·실수령 집계.")
    @GetMapping("/me/summary")
    public ResponseEntity<ApiResponse<List<SellerSettlementSummary>>> getMySummary(
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMySummary(SecurityUtil.getCurrentMemberId(), status, from, to)));
    }

    @Operation(summary = "내 지급 내역", description = "본인 셀러의 지급 묶음(Payout) 목록. 상태 필터(선택).")
    @GetMapping("/me/payouts")
    public ResponseEntity<ApiResponse<PageResponse<PayoutResponse>>> getMyPayouts(
            @RequestParam(required = false) PayoutStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMyPayouts(SecurityUtil.getCurrentMemberId(), status, pageable)));
    }

    @Operation(summary = "내 주문",
            description = "내 셀러 상품이 하나라도 든 주문 목록(무엇을 포장해 보낼지). keyword(수령인·주문번호)·"
                    + "status·기간·금액 필터. 셀러 스코프는 서버가 강제하므로 남의 셀러 주문은 보이지 않는다. 최신순.")
    @GetMapping("/me/orders")
    public ResponseEntity<ApiResponse<PageResponse<OrderSummaryResponse>>> getMyOrders(
            @ParameterObject OrderSearchCondition condition,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMyOrders(SecurityUtil.getCurrentMemberId(), condition, pageable)));
    }

    @Operation(summary = "내 배송 목록",
            description = "내 셀러의 배송 건(shipment) 목록 — 무엇을 포장해 보낼지 보는 화면. status로 상태 필터"
                    + "(PAID=출고 대기 / SHIPPING=배송중 / DELIVERED=완료 / CANCELLED). 셀러 스코프는 쿼리가 강제하며, "
                    + "응답은 전진 API와 같은 셀러 스코프(내 항목만·구매자 식별자 없이 배송지만).")
    @GetMapping("/me/shipments")
    public ResponseEntity<ApiResponse<PageResponse<SellerShipmentResponse>>> getMyShipments(
            @RequestParam(required = false) ShipmentStatus status,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMyShipments(SecurityUtil.getCurrentMemberId(), status, pageable)));
    }

    @Operation(summary = "내 배송 상태 전진",
            description = "내 셀러의 배송 건(shipment)을 다음 단계로 전진한다(PAID→SHIPPING→DELIVERED, forward-only). "
                    + "내 셀러 것이 아니거나 플랫폼 직매입 배송이면 403, 잘못된 전이면 409. SHIPPING일 때 택배사·운송장 실으면 저장된다.")
    // 📦 셀러가 자기 몫을 출고하는 지점 — 주체(셀러 회원)를 감사 이력에 남긴다.
    @Auditable(action = "SHIPMENT_ADVANCE", targetType = "SHIPMENT", targetId = "#shipmentId")
    @PatchMapping("/me/shipments/{shipmentId}/status")
    public ResponseEntity<ApiResponse<SellerShipmentResponse>> advanceMyShipment(
            @PathVariable Long shipmentId, @Valid @RequestBody ShipmentStatusUpdateRequest request) {
        SellerShipmentResponse response = sellerConsoleService.advanceMyShipment(
                SecurityUtil.getCurrentMemberId(), shipmentId,
                request.status(), request.courier(), request.trackingNumber());
        return ResponseEntity.ok(ApiResponse.success("배송 상태가 변경되었습니다.", response));
    }

    @Operation(summary = "내 반품/교환 목록",
            description = "내 셀러의 반품·교환 요청 목록. 셀러 스코프는 서버가 강제(남의 셀러 반품은 안 보임). 최신순.")
    @GetMapping("/me/returns")
    public ResponseEntity<ApiResponse<PageResponse<ReturnResponse>>> getMyReturns(
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                sellerConsoleService.getMyReturns(SecurityUtil.getCurrentMemberId(), pageable)));
    }

    @Operation(summary = "내 반품/교환 처리",
            description = "내 셀러의 반품·교환을 처리한다(APPROVE·REJECT·PICK_UP·INSPECT). 내 셀러 것이 아니면 403, "
                    + "잘못된 전이면 409. 환불/교환 확정(REFUND·COMPLETE)은 후속 단계.")
    @Auditable(action = "RETURN_ADVANCE", targetType = "RETURN", targetId = "#returnId")
    @PatchMapping("/me/returns/{returnId}/status")
    public ResponseEntity<ApiResponse<ReturnResponse>> advanceMyReturn(
            @PathVariable Long returnId, @Valid @RequestBody ReturnStatusUpdateRequest request) {
        ReturnResponse response = sellerConsoleService.advanceMyReturn(
                SecurityUtil.getCurrentMemberId(), returnId, request);
        return ResponseEntity.ok(ApiResponse.success("반품 상태가 변경되었습니다.", response));
    }
}
