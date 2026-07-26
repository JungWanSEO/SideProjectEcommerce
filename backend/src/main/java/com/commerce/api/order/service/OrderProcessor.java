package com.commerce.api.order.service;

import com.commerce.api.address.dto.AddressResponse;
import com.commerce.api.address.service.AddressService;
import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.cart.entity.Cart;
import com.commerce.api.cart.entity.CartItem;
import com.commerce.api.cart.repository.CartRepository;
import com.commerce.api.coupon.dto.CouponApplyResult;
import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.CheckoutRequest;
import com.commerce.api.order.dto.CouponPreviewResponse;
import com.commerce.api.order.dto.OrderCreateRequest;
import com.commerce.api.order.dto.OrderCreateRequest.OrderItemRequest;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.ShippingInfo;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.StockReservationService;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 주문 처리 트랜잭션 워커.
 *
 * OrderService.create/checkout가 @Retryable로 이 메서드를 호출한다. 재시도마다 새 트랜잭션으로 실행되도록
 * 트랜잭션 경계를 OrderService(재시도)와 분리한다 — 낙관적 락 충돌은 커밋 시점에 발생하므로
 * 같은 트랜잭션 안에서 재시도할 수 없기 때문.
 */
@Service
@RequiredArgsConstructor
public class OrderProcessor {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final AddressService addressService;   // 배송지 스냅샷(주소록에서) — 도메인 경계는 서비스+DTO로
    private final BrandRepository brandRepository;  // 주문 시점 셀러 귀속 스냅샷용(brandId→sellerId 조회)
    private final MemberCouponService memberCouponService;   // 쿠폰 적용/미리보기(공개형·발급형 분기·단일 사용) — 경계는 DTO로
    private final StockReservationService stockReservationService;   // 재고 예약(#2) — 주문 생성 시 오버셀 차단
    private final ShippingPolicy shippingPolicy;   // 배송비 정액+무료임계 계산(#4) — 주문 생성 시점 스냅샷

    /** 예약 만료 기준 = PENDING 결제 대기 TTL(OrderExpiryService와 같은 값). 만료 배치가 그때 예약을 해제한다. */
    @Value("${app.order.pending-ttl-minutes:30}")
    private int pendingTtlMinutes;

    /** 명시적 항목 목록으로 주문 생성 (POST /api/orders). 배송지·쿠폰은 없다(null). */
    @Transactional
    public OrderResponse place(Long memberId, OrderCreateRequest request) {
        return placeOrder(memberId, request.items(), null, null, null);   // 명시적 생성 경로엔 멱등키 없음
    }

    /**
     * 체크아웃: 서버의 장바구니를 읽어 그대로 주문 생성 + 장바구니 비우기 (한 트랜잭션).
     * 클라이언트는 항목을 보내지 않는다 — <b>서버 장바구니가 진실의 원천</b>(위변조 방지).
     * 배송지는 주소록(addressId)에서 골라 주문에 <b>스냅샷</b>한다. 주문 생성·장바구니 비우기가 원자적.
     */
    @Transactional
    public OrderResponse checkout(Long memberId, CheckoutRequest request) {
        // 멱등: 같은 키로 이미 만든 주문이 있으면 새로 만들지 않고 그 주문을 그대로 돌려준다.
        //   (더블클릭·타임아웃 후 재시도로 주문이 2건 생기고 장바구니가 두 번 비워지는 것을 막는다.)
        Optional<OrderResponse> replayed = findByIdempotencyKey(memberId, request.idempotencyKey());
        if (replayed.isPresent()) {
            return replayed.get();
        }

        Cart cart = cartRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "장바구니가 비어 있습니다."));

        List<OrderItemRequest> items = cart.getCartItems().stream()
                .map(ci -> new OrderItemRequest(ci.getOptionId(), ci.getQuantity()))
                .toList();
        if (items.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "장바구니가 비어 있습니다.");
        }

        // 본인 주소를 조회(없음 404·남의 것 403)해 배송지 스냅샷을 만든다.
        AddressResponse addr = addressService.getOwnedAddress(memberId, request.addressId());
        ShippingInfo shipping = ShippingInfo.of(addr.recipient(), addr.phone(), addr.zipcode(),
                addr.address1(), addr.address2(), request.deliveryMemo());

        OrderResponse response = placeOrder(memberId, items, shipping, request.couponCode(),
                request.idempotencyKey());
        cart.clearItems();   // 주문 성공 후 장바구니 비우기 (orphanRemoval로 DB 삭제, 같은 트랜잭션)
        return response;
    }

    /**
     * 멱등키로 이미 만들어진 주문을 찾는다(재시도 판정). 키가 없거나 처음 보는 키면 빈 Optional(= 새로 만들어라).
     *
     * <p><b>소유권 확인은 필수</b>: 멱등키는 전역 UNIQUE라, 남의 키를 추측·탈취해 보내면 <b>남의 주문이 그대로
     * 응답에 실릴 수 있다</b>(IDOR). 주인이 다르면 재시도가 아니라 키 충돌로 보고 409를 준다 —
     * 조회 결과를 숨기므로 "그 키가 존재하는지"조차 흘리지 않는다.
     *
     * <p>{@code OrderService}가 UNIQUE 위반(동시 중복 제출)을 잡은 뒤 <b>새 트랜잭션</b>으로 다시 부르므로 public.
     */
    @Transactional(readOnly = true)
    public Optional<OrderResponse> findByIdempotencyKey(Long memberId, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();   // 멱등키 미사용(구버전 클라이언트·내부 호출) — 기존 동작 그대로
        }
        return orderRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    if (!existing.getMemberId().equals(memberId)) {
                        throw new BusinessException(HttpStatus.CONFLICT, "이미 사용된 요청 키입니다.");
                    }
                    return OrderResponse.from(existing);
                });
    }

    /**
     * 쿠폰 미리보기 — 주문을 만들지 않고, 현재 서버 장바구니 기준으로 할인·예상 결제액만 계산한다.
     * 체크아웃과 같은 방식으로 총액·셀러별 소계를 만들어 쿠폰 도메인에 넘긴다(읽기 전용).
     * 적용 불가면 couponService가 400으로 사유를 던진다(체크아웃과 동일 검증).
     */
    @Transactional(readOnly = true)
    public CouponPreviewResponse previewCoupon(Long memberId, String couponCode) {
        Cart cart = cartRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "장바구니가 비어 있습니다."));
        if (cart.getCartItems().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "장바구니가 비어 있습니다.");
        }

        long total = 0;
        Map<Long, Long> grossBySeller = new HashMap<>();
        for (CartItem ci : cart.getCartItems()) {
            Product product = productRepository.findByOptionId(ci.getOptionId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                            "옵션을 찾을 수 없습니다. (id: " + ci.getOptionId() + ")"));
            long subtotal = product.getPrice() * ci.getQuantity();
            total += subtotal;
            Long brandId = product.getBrandId();
            Long sellerId = (brandId == null) ? null
                    : brandRepository.findById(brandId).map(Brand::getSellerId).orElse(null);
            grossBySeller.merge(sellerId, subtotal, Long::sum);
        }

        CouponApplyResult applied = memberCouponService.preview(memberId, couponCode, total, grossBySeller);
        long afterDiscount = total - applied.discountAmount();
        long shippingFee = shippingPolicy.feeFor(afterDiscount);   // 실제 주문과 같은 규칙(#4) — 프리뷰≠실결제 방지
        return new CouponPreviewResponse(applied.code(), total, applied.discountAmount(),
                shippingFee, afterDiscount + shippingFee);
    }

    /**
     * 항목마다 상품(옵션)을 조회해 주문 시점 스냅샷(상품명·사이즈·가격)을 남기고 주문에 추가한다.
     * 배송지(shipping)가 있으면 함께 스냅샷한다(체크아웃 경로). 명시적 주문 생성 경로는 null.
     * 쿠폰 코드(couponCode)가 있으면 항목 합산 후 할인을 적용한다(체크아웃 경로). 없으면 null.
     * 주문은 PENDING(결제 대기)으로 생성되며, <b>재고는 차감하지 않는다</b> — 재고 차감은 결제 승인(pay) 시점.
     */
    private OrderResponse placeOrder(Long memberId, List<OrderItemRequest> items, ShippingInfo shipping,
            String couponCode, String idempotencyKey) {
        Order order = Order.create(memberId);
        if (shipping != null) {
            order.ship(shipping);
        }
        if (StringUtils.hasText(idempotencyKey)) {
            order.assignIdempotencyKey(idempotencyKey);   // UNIQUE 위반 = 동시 중복 제출(OrderService가 처리)
        }

        for (OrderItemRequest itemRequest : items) {
            // 루트 경유: 옵션 ID로 Product 애그리거트를 로드(이름·가격 스냅샷에 어차피 필요)
            Product product = productRepository.findByOptionId(itemRequest.optionId())
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND,
                            "옵션을 찾을 수 없습니다. (id: " + itemRequest.optionId() + ")"));

            // 주문 시점 셀러 귀속 스냅샷: 상품→브랜드(brandId)→셀러(sellerId).
            // 브랜드 미지정(null) 또는 셀러 미귀속 브랜드면 sellerId는 null(미귀속 버킷).
            Long brandId = product.getBrandId();
            Long sellerId = (brandId == null) ? null
                    : brandRepository.findById(brandId).map(Brand::getSellerId).orElse(null);

            OrderItem orderItem = OrderItem.builder()
                    .productId(product.getId())
                    .optionId(itemRequest.optionId())
                    .brandId(brandId)                                     // 스냅샷
                    .sellerId(sellerId)                                   // 스냅샷(셀러별 정산 귀속)
                    .productName(product.getName())                       // 스냅샷
                    .size(product.optionSize(itemRequest.optionId()))     // 사이즈 스냅샷
                    .orderPrice(product.getPrice())                       // 스냅샷
                    .quantity(itemRequest.quantity())
                    .build();
            order.addItem(orderItem);
        }

        // 쿠폰 적용(선택): 셀러ID별 소계를 모아 쿠폰 도메인에 넘기면, 적용 대상 금액(플랫폼=주문총액 /
        // 셀러=그 셀러 소계)을 골라 할인액을 계산해 돌려준다(경계는 CouponApplyResult DTO로만).
        if (couponCode != null && !couponCode.isBlank()) {
            Map<Long, Long> grossBySeller = new HashMap<>();
            for (OrderItem item : order.getOrderItems()) {
                grossBySeller.merge(item.getSellerId(), item.getSubtotal(), Long::sum);
            }
            // 공개형이면 코드만으로, 발급형이면 보유(미사용) 검증 후 USED로 잠근다(단일 사용). 주문 tx와 원자적.
            CouponApplyResult applied =
                    memberCouponService.apply(memberId, couponCode, order.getTotalPrice(), grossBySeller);
            // 분담 주체는 enum 대신 이름(String)으로 스냅샷(order→coupon 결합 회피). 정산 분담(Step 2)이 읽는다.
            String fundedBy = applied.fundedBy() == null ? null : applied.fundedBy().name();
            order.applyCoupon(applied.code(), applied.discountAmount(), fundedBy, applied.sellerId());
        }

        // 배송비 스냅샷(#4): 정액+무료임계를 할인 후 상품금액(소계−할인) 기준으로 1회 계산해 고정한다.
        //   payable = 소계 − 할인 + 배송비. 이후 정책이 바뀌어도 이 주문의 결제액은 불변(discountAmount와 동형).
        order.assignShippingFee(shippingPolicy.feeFor(order.getTotalPrice() - order.getDiscountAmount()));

        Order saved = orderRepository.save(order);

        // 재고 예약(#2): 결제 전까지 이 주문 몫을 잡아 오버셀을 차단한다. 가용재고(stock−reserved)가 부족하면
        //   reserve가 409 → 주문 생성 트랜잭션 전체가 롤백(주문·쿠폰 사용까지 원복). 만료 배치가 TTL 후 해제.
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(pendingTtlMinutes);
        // optionId 오름차순으로 정규 락 순서를 강제한다 — 두 주문이 같은 옵션들을 서로 반대 순서로 담아도
        //   행 락을 항상 같은 순서로 잡아 InnoDB 데드락을 예방한다(데드락은 @Retryable이 재시도로도 흡수).
        saved.getOrderItems().stream()
                .sorted(Comparator.comparing(OrderItem::getOptionId))
                .forEach(item -> stockReservationService.reserve(
                        saved.getId(), item.getId(), item.getOptionId(), item.getQuantity(), expiresAt));
        return OrderResponse.from(saved);
    }

    /**
     * 결제 확정: 주문 생성 시 잡아 둔 재고 예약을 <b>실재고 차감으로 전환(소진)</b>하고 주문을 PAID로 만든다.
     *
     * <p>재고는 이미 주문 생성 시점에 예약(reserved)돼 있으므로 결제는 결정적이다 — "재고 부족"으로 실패하지 않는다
     * (오버셀 차단은 주문 생성 시 원자 예약으로 끝났다). 결제 전 취소된 항목은 그때 예약이 RELEASED됐으므로
     * ACTIVE 예약만 남아, {@code consumeForOrder}가 활성 항목만 정확히 소진한다(따로 isActive 필터가 필요 없다).
     */
    @Transactional
    public OrderResponse pay(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));

        stockReservationService.consumeForOrder(orderId);   // ACTIVE 예약 → 실재고 차감(stock↓·reserved↓)
        order.markPaid();   // PENDING → PAID
        return OrderResponse.from(order);
    }
}
