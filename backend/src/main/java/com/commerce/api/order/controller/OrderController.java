package com.commerce.api.order.controller;

import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.order.dto.CheckoutRequest;
import com.commerce.api.order.dto.CouponPreviewRequest;
import com.commerce.api.order.dto.CouponPreviewResponse;
import com.commerce.api.order.dto.OrderCreateRequest;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.dto.OrderSearchCondition;
import com.commerce.api.order.dto.OrderStatusUpdateRequest;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.dto.ShipmentStatusUpdateRequest;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.order.service.ShipmentService;
import com.commerce.api.payment.service.PaymentService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 API.
 * - POST /api/orders             주문 생성 (명시적 항목)
 * - POST /api/orders/checkout    장바구니 체크아웃 (장바구니 → 주문 + 비우기)
 * - GET  /api/orders             내 주문 목록 (페이지)
 * - GET  /api/orders/{id}        단건 조회
 * - POST /api/orders/{id}/cancel 주문 취소
 */
@Tag(name = "주문(Order)", description = "주문 생성 / 조회 / 취소 API")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;   // 취소+환불 오케스트레이션은 결제 측에 위임(순환 의존 회피)
    private final ShipmentService shipmentService;   // 배송 건(shipment) 단위 전진(#1 c안)

    @Operation(summary = "주문 생성", description = "상품 ID·수량 목록으로 주문한다. 주문자는 로그인 사용자. 주문은 결제 대기(PENDING)로 생성되며 주문 시점 가격을 스냅샷한다. 재고 차감은 결제 승인 시점에 일어난다.")
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> create(
            @Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.create(SecurityUtil.getCurrentMemberId(), request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("주문이 접수되었습니다. (결제 대기)", response));
    }

    @Operation(summary = "장바구니 체크아웃",
            description = "로그인 사용자의 장바구니를 주문으로 만들고 장바구니를 비운다(한 트랜잭션). "
                    + "주문 항목은 서버의 장바구니에서 가져온다(클라이언트가 항목을 보내지 않음). 배송지는 주소록 항목(addressId)에서 "
                    + "골라 주문에 스냅샷한다. 빈 장바구니면 400, 본인 주소가 아니면 403. "
                    + "주문은 결제 대기(PENDING)로 생성된다(재고 차감은 결제 승인 시).")
    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<OrderResponse>> checkout(
            @Valid @RequestBody CheckoutRequest request) {
        OrderResponse response = orderService.checkout(SecurityUtil.getCurrentMemberId(), request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("주문이 접수되었습니다. (결제 대기)", response));
    }

    @Operation(summary = "쿠폰 미리보기",
            description = "주문을 만들지 않고 현재 장바구니에 쿠폰 코드를 적용했을 때의 할인·예상 결제액을 계산한다. "
                    + "적용 불가(코드 없음·기간 외·최소금액 미달·대상 셀러 상품 없음)면 400으로 사유 반환.")
    @PostMapping("/coupon-preview")
    public ResponseEntity<ApiResponse<CouponPreviewResponse>> previewCoupon(
            @Valid @RequestBody CouponPreviewRequest request) {
        CouponPreviewResponse response =
                orderService.previewCoupon(SecurityUtil.getCurrentMemberId(), request.couponCode());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "내 주문 목록 조회 (요약)",
            description = "로그인 사용자 본인의 주문을 페이지로 조회한다. 목록은 요약(대표상품명·항목수 등)만 — "
                    + "전체 항목은 단건 조회로. 기본 정렬은 최신순(createdAt desc), 기본 페이지 크기는 20. "
                    + "page/size/sort 파라미터로 변경 가능 (예: ?page=0&size=10&sort=createdAt,desc).")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<OrderSummaryResponse>>> getMyOrders(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        PageResponse<OrderSummaryResponse> response =
                orderService.getMyOrders(SecurityUtil.getCurrentMemberId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "주문 검색 (ADMIN)",
            description = "운영자가 모든 회원의 주문을 조건으로 검색한다(CS·배송 관리). 선택 필터: keyword(수령인명 또는 "
                    + "주문번호)·memberId·status·from/to(생성일, yyyy-MM-dd)·minAmount/maxAmount(총액). "
                    + "비우면 전체. 기본 정렬 최신순, 기본 페이지 크기 20.")
    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<PageResponse<OrderSummaryResponse>>> searchOrders(
            @ParameterObject OrderSearchCondition condition,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        // sellerId는 셀러 콘솔 전용 파라미터 → 어드민 검색에선 무시(아무나 셀러 스코프를 흉내내지 못하게).
        OrderSearchCondition adminCondition = new OrderSearchCondition(
                condition.keyword(), condition.memberId(), condition.status(),
                condition.from(), condition.to(), condition.minAmount(), condition.maxAmount(), null);
        PageResponse<OrderSummaryResponse> response = orderService.searchOrders(adminCondition, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "주문 단건 조회",
            description = "주문 ID로 주문 정보를 조회한다. 본인 주문 또는 ADMIN만 가능(아니면 403). 없으면 404.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable Long id) {
        OrderResponse response = orderService.getOrder(
                id, SecurityUtil.getCurrentMemberId(), SecurityUtil.isAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "주문 취소",
            description = "주문을 취소한다. 결제 완료(PAID) 주문이면 차감했던 재고를 복원하고 결제를 환불(PG 취소)한다. "
                    + "본인 주문 또는 ADMIN만 가능(아니면 403). 이미 취소된 주문이면 409. 환불 실패 시 502(전체 롤백).")
    // 💸 돈이 되돌아가는 지점(환불) — 본인/ADMIN 누가 실행했든 감사 이력을 남긴다.
    @Auditable(action = "ORDER_CANCEL", targetType = "ORDER", targetId = "#id")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancel(@PathVariable Long id) {
        OrderResponse response = paymentService.cancelOrder(
                SecurityUtil.getCurrentMemberId(), id, SecurityUtil.isAdmin());
        return ResponseEntity.ok(ApiResponse.success("주문이 취소되었습니다.", response));
    }

    @Operation(summary = "주문 항목 부분 취소(환불)",
            description = "주문의 특정 항목(라인)만 취소·환불한다. PAID 주문이면 그 항목 재고 복원 + 금액만큼 부분 환불. "
                    + "본인 주문 또는 ADMIN만(아니면 403). 이미 취소된 항목이면 409.")
    @Auditable(action = "ORDER_ITEM_CANCEL", targetType = "ORDER", targetId = "#orderId")
    @PostMapping("/{orderId}/items/{itemId}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelItem(
            @PathVariable Long orderId, @PathVariable Long itemId) {
        OrderResponse response = paymentService.cancelOrderItem(
                SecurityUtil.getCurrentMemberId(), orderId, itemId, SecurityUtil.isAdmin());
        return ResponseEntity.ok(ApiResponse.success("주문 항목이 취소(환불)되었습니다.", response));
    }

    @Operation(summary = "주문 배송 상태 전진 (ADMIN)",
            description = "주문 배송 상태를 다음 단계로 전진한다(PAID→SHIPPING→DELIVERED, forward-only). "
                    + "SHIPPING으로 보낼 때 택배사·운송장을 함께 실으면 주문에 저장돼 구매자에게 노출된다(선택). "
                    + "운영자만 가능. 없는 주문이면 404, 잘못된 전이(건너뛰기·되돌리기·취소/대기 상태)면 409.")
    @Auditable(action = "ORDER_ADVANCE_SHIPPING", targetType = "ORDER", targetId = "#id")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<OrderResponse>> advanceShipping(
            @PathVariable Long id, @Valid @RequestBody OrderStatusUpdateRequest request) {
        OrderResponse response = orderService.advanceShipping(
                id, request.status(), SecurityUtil.getCurrentMemberId(),
                request.courier(), request.trackingNumber());
        return ResponseEntity.ok(ApiResponse.success("주문 상태가 변경되었습니다.", response));
    }

    @Operation(summary = "배송 건 상태 전진 (ADMIN)",
            description = "멀티셀러 주문의 특정 배송 건(shipment)을 다음 단계로 전진한다(PAID→SHIPPING→DELIVERED). "
                    + "셀러별로 개별 출고할 때·플랫폼 직매입(셀러 미귀속) 배송을 운영자가 처리할 때 쓴다. "
                    + "주문 전체 일괄 전진은 PATCH /{id}/status. 경로의 주문과 배송 건이 안 맞으면 404, 잘못된 전이면 409.")
    @Auditable(action = "SHIPMENT_ADVANCE", targetType = "SHIPMENT", targetId = "#shipmentId")
    @PatchMapping("/{orderId}/shipments/{shipmentId}/status")
    public ResponseEntity<ApiResponse<OrderResponse>> advanceShipment(
            @PathVariable Long orderId, @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentStatusUpdateRequest request) {
        OrderResponse response = shipmentService.advanceForAdmin(
                orderId, shipmentId, request.status(), SecurityUtil.getCurrentMemberId(),
                request.courier(), request.trackingNumber());
        return ResponseEntity.ok(ApiResponse.success("배송 상태가 변경되었습니다.", response));
    }
}