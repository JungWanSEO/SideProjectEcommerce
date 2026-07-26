package com.commerce.api.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.cart.dto.CartItemAddRequest;
import com.commerce.api.cart.dto.CartItemUpdateRequest;
import com.commerce.api.cart.dto.CartResponse;
import com.commerce.api.cart.entity.Cart;
import com.commerce.api.cart.repository.CartRepository;
import com.commerce.api.global.exception.BusinessException;
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
 * CartService 단위 테스트.
 * 장바구니 식별 단위는 옵션(사이즈) — 담기/제거/dedup 모두 optionId 기준.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CartService cartService;

    /** 옵션 1개를 가진 상품 생성 (id는 JPA 자동생성이라 리플렉션으로 주입). */
    private Product productWithOption(Long productId, Long optionId, String name, long price,
                                      String size, int stock) {
        Product product = Product.builder()
                .name(name).price(price).description("desc")
                .status(ProductStatus.ON_SALE).build();
        ReflectionTestUtils.setField(product, "id", productId);
        ProductOption option = ProductOption.create(size, stock);
        ReflectionTestUtils.setField(option, "id", optionId);
        product.addOption(option);   // 양방향 연관 + options 목록에 추가
        return product;
    }

    @Test
    @DisplayName("담기 - 장바구니가 없으면 새로 만들어 옵션 항목을 추가한다")
    void addItem_newCart() {
        // given: 옵션 10(상품 1, 사이즈 M, 재고 50)
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, "M", 50);
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.empty());
        given(cartRepository.save(any(Cart.class))).willAnswer(inv -> inv.getArgument(0));
        given(productRepository.findAllById(any())).willReturn(List.of(product));

        // when
        CartResponse response = cartService.addItem(CartOwner.member(100L), new CartItemAddRequest(10L, 2));

        // then: 항목에 상품명·사이즈·재고가 라이브로 채워짐
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).optionId()).isEqualTo(10L);
        assertThat(response.items().get(0).productName()).isEqualTo("반팔티셔츠");
        assertThat(response.items().get(0).size()).isEqualTo("M");
        assertThat(response.items().get(0).stock()).isEqualTo(50);
        assertThat(response.items().get(0).soldOut()).isFalse();
        assertThat(response.items().get(0).subtotal()).isEqualTo(20000L);  // 10000 * 2
        assertThat(response.totalQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("담기 - 이미 담긴 옵션이면 수량이 증가한다")
    void addItem_existingOption_increasesQuantity() {
        // given: 이미 옵션 10을 2개 담은 장바구니
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, "M", 50);
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 2);
        given(productRepository.findByOptionId(10L)).willReturn(Optional.of(product));
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(cartRepository.save(any(Cart.class))).willAnswer(inv -> inv.getArgument(0));
        given(productRepository.findAllById(any())).willReturn(List.of(product));

        // when: 같은 옵션 3개 추가
        CartResponse response = cartService.addItem(CartOwner.member(100L), new CartItemAddRequest(10L, 3));

        // then: 항목은 1개, 수량은 5
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(5);
        assertThat(response.totalQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("담기 실패 - 존재하지 않는 옵션")
    void addItem_optionNotFound() {
        // given
        given(productRepository.findByOptionId(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> cartService.addItem(CartOwner.member(100L), new CartItemAddRequest(99L, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("옵션을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("조회 - 장바구니가 없으면 빈 장바구니를 반환한다")
    void getCart_empty() {
        // given
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.empty());

        // when
        CartResponse response = cartService.getCart(CartOwner.member(100L));

        // then
        assertThat(response.items()).isEmpty();
        assertThat(response.totalQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("수량 변경 - 절대값으로 덮어쓴다(가산이 아님)")
    void changeQuantity_success() {
        // given: 옵션 10을 2개 담은 장바구니
        Product product = productWithOption(1L, 10L, "반팔티셔츠", 10000L, "M", 50);
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 2);
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(productRepository.findAllById(any())).willReturn(List.of(product));

        // when: 수량을 5로 변경
        CartResponse response = cartService.changeQuantity(CartOwner.member(100L), 10L, new CartItemUpdateRequest(5));

        // then: 2+5=7이 아니라 5로 덮어써짐
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(5);
        assertThat(response.items().get(0).subtotal()).isEqualTo(50000L);  // 10000 * 5
        assertThat(response.totalQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("수량 변경 실패 - 장바구니에 해당 옵션 항목이 없으면 예외")
    void changeQuantity_itemNotFound() {
        // given: 옵션 10만 담긴 장바구니
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 2);
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));

        // when & then: 담기지 않은 옵션 99 변경 시도
        assertThatThrownBy(() -> cartService.changeQuantity(CartOwner.member(100L), 99L, new CartItemUpdateRequest(3)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("해당 옵션 항목이 없습니다");
    }

    @Test
    @DisplayName("수량 변경 실패 - 장바구니가 없으면 예외")
    void changeQuantity_noCart() {
        // given
        given(cartRepository.findByMemberId(anyLong())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> cartService.changeQuantity(CartOwner.member(100L), 10L, new CartItemUpdateRequest(3)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("장바구니가 없습니다");
    }

    @Test
    @DisplayName("항목 제거 - 해당 옵션이 장바구니에서 빠진다")
    void removeItem_success() {
        // given: 옵션 10(상품 1), 옵션 20(상품 2)을 담은 장바구니
        Cart cart = Cart.create(100L);
        cart.addItem(1L, 10L, 2);
        cart.addItem(2L, 20L, 1);
        Product product2 = productWithOption(2L, 20L, "청바지", 20000L, "L", 30);
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(cart));
        given(productRepository.findAllById(any())).willReturn(List.of(product2));

        // when: 옵션 10 제거
        CartResponse response = cartService.removeItem(CartOwner.member(100L), 10L);

        // then: 옵션 20만 남음
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).optionId()).isEqualTo(20L);
        assertThat(response.items().get(0).productId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("항목 제거 실패 - 장바구니가 없으면 예외")
    void removeItem_noCart() {
        // given
        given(cartRepository.findByMemberId(anyLong())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> cartService.removeItem(CartOwner.member(100L), 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("장바구니가 없습니다");
    }

    // === 게스트 → 회원 병합(#7) ===

    private int qtyOf(Cart cart, long optionId) {
        return cart.getCartItems().stream()
                .filter(i -> i.getOptionId().equals(optionId)).findFirst().orElseThrow().getQuantity();
    }

    @Test
    @DisplayName("병합 - 게스트 카트를 회원 카트로 합산(같은 옵션 수량 더함)하고 게스트 카트 삭제")
    void mergeGuestIntoMember_sums() {
        Cart guest = Cart.createForGuest("tok");
        guest.addItem(1L, 10L, 2);   // 옵션10 ×2
        guest.addItem(2L, 20L, 1);   // 옵션20 ×1
        Cart member = Cart.create(100L);
        member.addItem(1L, 10L, 1);  // 옵션10 ×1 (겹침)
        given(cartRepository.findByCartToken("tok")).willReturn(Optional.of(guest));
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.of(member));

        cartService.mergeGuestIntoMember("tok", 100L);

        assertThat(member.getCartItems()).hasSize(2);
        assertThat(qtyOf(member, 10L)).isEqualTo(3);   // 1 + 2 합산
        assertThat(qtyOf(member, 20L)).isEqualTo(1);   // 게스트에서 이전
        verify(cartRepository).save(member);
        verify(cartRepository).delete(guest);          // 병합 후 게스트 카트 제거
    }

    @Test
    @DisplayName("병합 - 회원 카트가 없으면 새로 만들어 게스트 항목을 옮긴다")
    void mergeGuestIntoMember_newMemberCart() {
        Cart guest = Cart.createForGuest("tok");
        guest.addItem(1L, 10L, 2);
        given(cartRepository.findByCartToken("tok")).willReturn(Optional.of(guest));
        given(cartRepository.findByMemberId(100L)).willReturn(Optional.empty());
        given(cartRepository.save(any(Cart.class))).willAnswer(inv -> inv.getArgument(0));

        cartService.mergeGuestIntoMember("tok", 100L);

        verify(cartRepository).delete(guest);
    }

    @Test
    @DisplayName("병합 - 게스트 카트가 없으면 no-op(저장·삭제 없음)")
    void mergeGuestIntoMember_noGuest() {
        given(cartRepository.findByCartToken("tok")).willReturn(Optional.empty());

        cartService.mergeGuestIntoMember("tok", 100L);

        verify(cartRepository, never()).save(any());
        verify(cartRepository, never()).delete(any());
    }
}
