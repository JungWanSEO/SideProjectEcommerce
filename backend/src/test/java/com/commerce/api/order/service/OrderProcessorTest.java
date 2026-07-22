package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.address.dto.AddressResponse;
import com.commerce.api.address.service.AddressService;
import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.cart.entity.Cart;
import com.commerce.api.cart.repository.CartRepository;
import com.commerce.api.coupon.dto.CouponApplyResult;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.CheckoutRequest;
import com.commerce.api.order.dto.CouponPreviewResponse;
import com.commerce.api.order.dto.OrderCreateRequest;
import com.commerce.api.order.dto.OrderCreateRequest.OrderItemRequest;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OrderProcessor 단위 테스트.
 * 주문 생성(place/checkout)은 PENDING + 스냅샷만(재고 미차감), 재고 차감은 결제 확정(pay) 시점.
 */
@ExtendWith(MockitoExtension.class)
class OrderProcessorTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private AddressService addressService;
    @Mock
    private BrandRepository brandRepository;
    @Mock
    private MemberCouponService memberCouponService;
    @Mock
    private com.commerce.api.product.service.StockReservationService stockReservationService;

    @InjectMocks
    private OrderProcessor orderProcessor;

    @org.junit.jupiter.api.BeforeEach
    void setTtl() {
        ReflectionTestUtils.setField(orderProcessor, "pendingTtlMinutes", 30);   // @Value는 단위 테스트에서 미주입
    }

    /** id가 채워진 옵션("M") 1개를 가진 상품 생성. */
    private Product productWithOption(Long productId, Long optionId, String name, long price, int stock) {
        Product product = Product.builder()
                .name(name).price(price).description("desc").status(ProductStatus.ON_SALE).build();
        ReflectionTestUtils.setField(product, "id", productId);
        ProductOption option = ProductOption.create("M", stock);
        ReflectionTestUtils.setField(option, "id", optionId);
        product.addOption(option);
        return product;
    }

    /** id가 채워진 PENDING 주문(옵션 1개) 생성 — pay 테스트용. */
    private Order pendingOrder(Long orderId, Long optionId, int quantity) {
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(optionId).productName("반팔티셔츠").size("M")
                .orderPrice(10000L).quantity(quantity).build());
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }

    @Test
    @DisplayName("주문 생성 - PENDING(재고 미차감), 가격·사이즈 스냅샷, 총액 계산")
    void place_success() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderProcessor.place(100L,
                new OrderCreateRequest(List.of(new OrderItemRequest(10L, 3))));

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalPrice()).isEqualTo(30000L);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productName()).isEqualTo("반팔티셔츠");
        assertThat(response.items().get(0).size()).isEqualTo("M");
        assertThat(response.items().get(0).subtotal()).isEqualTo(30000L);
        assertThat(product.getOptions().get(0).getStock()).isEqualTo(10);   // 재고 미차감(결제 시 차감)
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("주문 생성 - 셀러 귀속 스냅샷(상품→brandId→sellerId)")
    void place_snapshotsSeller() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        ReflectionTestUtils.setField(product, "brandId", 7L);   // 상품에 브랜드 귀속
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        Brand brand = Brand.create("Nike");
        brand.assignSeller(3L);                                  // 브랜드 → 셀러 3
        given(brandRepository.findById(7L)).willReturn(Optional.of(brand));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderProcessor.place(100L,
                new OrderCreateRequest(List.of(new OrderItemRequest(10L, 1))));

        assertThat(response.items().get(0).brandId()).isEqualTo(7L);
        assertThat(response.items().get(0).sellerId()).isEqualTo(3L);   // 주문 시점 셀러 동결
    }

    @Test
    @DisplayName("주문 생성 - 브랜드 미지정 상품이면 brandId·sellerId 모두 null(브랜드 조회 안 함)")
    void place_noBrand_nullSeller() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);   // brandId null
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderProcessor.place(100L,
                new OrderCreateRequest(List.of(new OrderItemRequest(10L, 1))));

        assertThat(response.items().get(0).brandId()).isNull();
        assertThat(response.items().get(0).sellerId()).isNull();
        verify(brandRepository, never()).findById(any());
    }

    @Test
    @DisplayName("주문 생성 실패 - 존재하지 않는 옵션 (스냅샷용 조회 실패)")
    void place_optionNotFound() {
        given(productRepository.findByOptionId(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderProcessor.place(100L,
                new OrderCreateRequest(List.of(new OrderItemRequest(99L, 1)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("옵션을 찾을 수 없습니다");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("체크아웃 - 장바구니를 PENDING 주문으로 만들고 배송지 스냅샷 + 장바구니 비우기 (재고 미차감)")
    void checkout_createsOrderAndClearsCart() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 2);   // (productId, optionId, quantity)
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        // 주소록(addressId=5)에서 배송지를 가져와 스냅샷
        given(addressService.getOwnedAddress(100L, 5L)).willReturn(
                new AddressResponse(5L, "홍길동", "010-1234-5678", "06236", "서울 강남구", "4층", true, null));

        OrderResponse response = orderProcessor.checkout(100L, new CheckoutRequest(5L, "문 앞에 놔주세요", null, null));

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalPrice()).isEqualTo(20000L);              // 10000 x 2
        assertThat(response.discountAmount()).isEqualTo(0L);              // 쿠폰 없음
        assertThat(response.payableAmount()).isEqualTo(20000L);           // 할인 없으면 payable = 총액
        assertThat(response.items()).hasSize(1);
        assertThat(product.getOptions().get(0).getStock()).isEqualTo(10); // 재고 미차감(결제 시 차감)
        assertThat(cart.getCartItems()).isEmpty();                        // 장바구니 비워짐
        // 배송지 스냅샷이 응답에 반영됨
        assertThat(response.shipping()).isNotNull();
        assertThat(response.shipping().recipient()).isEqualTo("홍길동");
        assertThat(response.shipping().address1()).isEqualTo("서울 강남구");
        assertThat(response.shipping().deliveryMemo()).isEqualTo("문 앞에 놔주세요");
    }

    @Test
    @DisplayName("체크아웃 - 쿠폰 코드가 있으면 할인 적용(payable=총액-할인) + 코드 스냅샷, 항목 소계(gross)는 보존")
    void checkout_appliesCoupon() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 2);   // 총액 20000
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(addressService.getOwnedAddress(100L, 5L)).willReturn(
                new AddressResponse(5L, "홍길동", "010-1234-5678", "06236", "서울 강남구", "4층", true, null));
        // 쿠폰 도메인(MemberCouponService)이 적용 대상 금액(주문 총액 20000)으로 5000 할인을 돌려준다(플랫폼 부담).
        given(memberCouponService.apply(eq(100L), eq("WELCOME5000"), eq(20000L), anyMap()))
                .willReturn(new CouponApplyResult("WELCOME5000", 5000L, CouponFundedBy.PLATFORM, null));

        OrderResponse response = orderProcessor.checkout(100L,
                new CheckoutRequest(5L, null, "WELCOME5000", null));

        assertThat(response.totalPrice()).isEqualTo(20000L);     // gross(항목 소계 합) 보존
        assertThat(response.discountAmount()).isEqualTo(5000L);
        assertThat(response.payableAmount()).isEqualTo(15000L);  // 20000 - 5000 = 실제 결제액
        assertThat(response.couponCode()).isEqualTo("WELCOME5000");
        assertThat(response.items().get(0).subtotal()).isEqualTo(20000L);   // 항목 원가는 안 깎임(정산 Step 2 기준)
    }

    // === 멱등키(중복 주문 방지) ===

    @Test
    @DisplayName("체크아웃 멱등 - 같은 키로 다시 오면 새 주문을 만들지 않고 기존 주문을 그대로 돌려준다(장바구니도 안 건드림)")
    void checkout_replaysExistingOrderForSameKey() {
        Order existing = Order.create(100L);
        existing.addItem(OrderItem.builder()
                .productId(1L).optionId(10L).productName("반팔티셔츠").size("M")
                .orderPrice(10000L).quantity(2).build());
        ReflectionTestUtils.setField(existing, "id", 77L);
        given(orderRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(existing));

        OrderResponse response = orderProcessor.checkout(100L,
                new CheckoutRequest(5L, null, null, "key-1"));

        assertThat(response.id()).isEqualTo(77L);                    // 처음 만든 그 주문
        verify(orderRepository, never()).save(any(Order.class));     // 새 주문 없음
        verify(cartRepository, never()).findByMemberId(any());       // 장바구니를 다시 비우지도 않음
    }

    @Test
    @DisplayName("체크아웃 멱등 - 남의 멱등키면 그 주문을 돌려주지 않고 409(IDOR 차단)")
    void checkout_rejectsOtherMembersKey() {
        Order othersOrder = Order.create(999L);   // 다른 회원의 주문
        ReflectionTestUtils.setField(othersOrder, "id", 88L);
        given(orderRepository.findByIdempotencyKey("stolen-key")).willReturn(Optional.of(othersOrder));

        assertThatThrownBy(() -> orderProcessor.checkout(100L,
                new CheckoutRequest(5L, null, null, "stolen-key")))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("체크아웃 멱등 - 처음 보는 키면 주문을 만들고 그 키를 주문에 새긴다")
    void checkout_stampsIdempotencyKeyOnNewOrder() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 1);
        given(orderRepository.findByIdempotencyKey("fresh-key")).willReturn(Optional.empty());
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(addressService.getOwnedAddress(100L, 5L)).willReturn(
                new AddressResponse(5L, "홍길동", "010-1234-5678", "06236", "서울 강남구", "4층", true, null));

        orderProcessor.checkout(100L, new CheckoutRequest(5L, null, null, "fresh-key"));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("fresh-key");
    }

    @Test
    @DisplayName("체크아웃 멱등 - 키가 없으면(구버전 클라이언트) 조회 없이 기존 동작 그대로")
    void checkout_withoutKeySkipsLookup() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 1);
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(addressService.getOwnedAddress(100L, 5L)).willReturn(
                new AddressResponse(5L, "홍길동", "010-1234-5678", "06236", "서울 강남구", "4층", true, null));

        orderProcessor.checkout(100L, new CheckoutRequest(5L, null, null, null));

        verify(orderRepository, never()).findByIdempotencyKey(any());
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("체크아웃 실패 - 빈 장바구니면 400, 저장 안 됨 (주소 조회 전에 막힘)")
    void checkout_emptyCart() {
        Cart cart = Cart.create(100L);   // 항목 없음
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));

        assertThatThrownBy(() -> orderProcessor.checkout(100L, new CheckoutRequest(5L, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("장바구니가 비어 있습니다");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("쿠폰 미리보기 - 장바구니 기준 할인·예상 결제액 계산(주문 생성 없음)")
    void previewCoupon_success() {
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 2);   // 총 20000
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(memberCouponService.preview(eq(100L), eq("WELCOME5000"), eq(20000L), anyMap()))
                .willReturn(new CouponApplyResult("WELCOME5000", 5000L, CouponFundedBy.PLATFORM, null));

        CouponPreviewResponse preview = orderProcessor.previewCoupon(100L, "WELCOME5000");

        assertThat(preview.couponCode()).isEqualTo("WELCOME5000");
        assertThat(preview.totalPrice()).isEqualTo(20000L);
        assertThat(preview.discountAmount()).isEqualTo(5000L);
        assertThat(preview.payableAmount()).isEqualTo(15000L);
        verify(orderRepository, never()).save(any(Order.class));   // 주문 생성 없음
    }

    @Test
    @DisplayName("쿠폰 미리보기 실패 - 빈 장바구니면 400")
    void previewCoupon_emptyCart() {
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(Cart.create(100L)));

        assertThatThrownBy(() -> orderProcessor.previewCoupon(100L, "ANY"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("장바구니가 비어 있습니다");
    }

    @Test
    @DisplayName("결제 확정 - 예약을 실재고 차감으로 소진 + 주문 PAID")
    void pay_success() {
        Order order = pendingOrder(1L, 10L, 3);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        OrderResponse response = orderProcessor.pay(1L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        // 재고는 주문 생성 시 이미 예약됨 → 결제는 그 예약을 소진(실차감)한다. 재고 부족으로 실패하지 않는다.
        verify(stockReservationService).consumeForOrder(1L);
    }

    @Test
    @DisplayName("결제 확정 실패 - 없는 주문")
    void pay_orderNotFound() {
        given(orderRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderProcessor.pay(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("주문을 찾을 수 없습니다");
    }
}
