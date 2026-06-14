package com.commerce.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks
    private OrderProcessor orderProcessor;

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

        OrderResponse response = orderProcessor.checkout(100L, new CheckoutRequest(5L, "문 앞에 놔주세요"));

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalPrice()).isEqualTo(20000L);              // 10000 x 2
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
    @DisplayName("체크아웃 실패 - 빈 장바구니면 400, 저장 안 됨 (주소 조회 전에 막힘)")
    void checkout_emptyCart() {
        Cart cart = Cart.create(100L);   // 항목 없음
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));

        assertThatThrownBy(() -> orderProcessor.checkout(100L, new CheckoutRequest(5L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("장바구니가 비어 있습니다");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("결제 확정 - 옵션 재고 차감 + 주문 PAID")
    void pay_success() {
        Order order = pendingOrder(1L, 10L, 3);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 10);
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));

        OrderResponse response = orderProcessor.pay(1L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(product.getOptions().get(0).getStock()).isEqualTo(7);   // 10 - 3
    }

    @Test
    @DisplayName("결제 확정 실패 - 재고 부족이면 예외, 주문은 PENDING 유지")
    void pay_insufficientStock() {
        Order order = pendingOrder(1L, 10L, 5);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, 2);
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));

        assertThatThrownBy(() -> orderProcessor.pay(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("재고가 부족합니다");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
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
