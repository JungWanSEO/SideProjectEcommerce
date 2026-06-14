package com.commerce.api.order.service;

import com.commerce.api.address.dto.AddressResponse;
import com.commerce.api.address.service.AddressService;
import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.cart.entity.Cart;
import com.commerce.api.cart.repository.CartRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.CheckoutRequest;
import com.commerce.api.order.dto.OrderCreateRequest;
import com.commerce.api.order.dto.OrderCreateRequest.OrderItemRequest;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.ShippingInfo;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 명시적 항목 목록으로 주문 생성 (POST /api/orders). 배송지는 없다(null). */
    @Transactional
    public OrderResponse place(Long memberId, OrderCreateRequest request) {
        return placeOrder(memberId, request.items(), null);
    }

    /**
     * 체크아웃: 서버의 장바구니를 읽어 그대로 주문 생성 + 장바구니 비우기 (한 트랜잭션).
     * 클라이언트는 항목을 보내지 않는다 — <b>서버 장바구니가 진실의 원천</b>(위변조 방지).
     * 배송지는 주소록(addressId)에서 골라 주문에 <b>스냅샷</b>한다. 주문 생성·장바구니 비우기가 원자적.
     */
    @Transactional
    public OrderResponse checkout(Long memberId, CheckoutRequest request) {
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

        OrderResponse response = placeOrder(memberId, items, shipping);
        cart.clearItems();   // 주문 성공 후 장바구니 비우기 (orphanRemoval로 DB 삭제, 같은 트랜잭션)
        return response;
    }

    /**
     * 항목마다 상품(옵션)을 조회해 주문 시점 스냅샷(상품명·사이즈·가격)을 남기고 주문에 추가한다.
     * 배송지(shipping)가 있으면 함께 스냅샷한다(체크아웃 경로). 명시적 주문 생성 경로는 null.
     * 주문은 PENDING(결제 대기)으로 생성되며, <b>재고는 차감하지 않는다</b> — 재고 차감은 결제 승인(pay) 시점.
     */
    private OrderResponse placeOrder(Long memberId, List<OrderItemRequest> items, ShippingInfo shipping) {
        Order order = Order.create(memberId);
        if (shipping != null) {
            order.ship(shipping);
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

        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * 결제 확정: 주문의 각 항목 재고를 차감(@Version 낙관적 락)하고 주문을 PAID로 만든다.
     * 동시 차감 충돌은 커밋 시 OptimisticLockingFailureException → 상위(OrderService.pay)에서 재시도.
     * 재고 부족이면 BusinessException(409)으로 롤백 → 주문은 PENDING으로 남는다(재결제 가능).
     */
    @Transactional
    public OrderResponse pay(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));

        for (OrderItem item : order.getOrderItems()) {
            // 루트 경유: 옵션 ID로 Product 애그리거트를 로드해 해당 옵션 재고를 차감
            Product product = productRepository.findByOptionId(item.getOptionId())
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "옵션을 찾을 수 없습니다. (id: " + item.getOptionId() + ")"));
            product.decreaseStock(item.getOptionId(), item.getQuantity());
        }
        order.markPaid();   // PENDING → PAID
        return OrderResponse.from(order);
    }
}
