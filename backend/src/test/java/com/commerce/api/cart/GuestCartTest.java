package com.commerce.api.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.cart.dto.CartItemAddRequest;
import com.commerce.api.cart.dto.CartResponse;
import com.commerce.api.cart.entity.Cart;
import com.commerce.api.cart.repository.CartRepository;
import com.commerce.api.cart.service.CartOwner;
import com.commerce.api.cart.service.CartService;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게스트 장바구니(#7) end-to-end 통합 테스트 — 게스트 담기(토큰 카트 생성)·로그인 병합(합산+게스트 삭제)을
 * 실제 CartService·리포지토리(findByCartToken/delete)로 검증한다.
 */
@SpringBootTest
@Transactional
class GuestCartTest {

    @Autowired private CartService cartService;
    @Autowired private CartRepository cartRepository;
    @Autowired private ProductRepository productRepository;

    private long optionId() {
        Product product = Product.builder().name("셔츠").price(5000L).description("d").status(ProductStatus.ON_SALE).build();
        product.addOption(ProductOption.create("M", 100));
        return productRepository.save(product).getOptions().get(0).getId();
    }

    @Test
    @DisplayName("게스트 담기 - cart_token으로 게스트 장바구니가 생성된다(memberId 없음)")
    void guestAdd_createsTokenCart() {
        long option = optionId();
        CartResponse resp = cartService.addItem(CartOwner.guest("g-token-1"), new CartItemAddRequest(option, 2));

        assertThat(resp.memberId()).isNull();
        Cart guest = cartRepository.findByCartToken("g-token-1").orElseThrow();
        assertThat(guest.getMemberId()).isNull();
        assertThat(guest.getCartItems()).hasSize(1);
        assertThat(guest.getCartItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("로그인 병합 - 게스트 항목을 회원 카트로 합산하고 게스트 카트는 삭제")
    void merge_sumsAndDeletesGuest() {
        Cart guest = Cart.createForGuest("g-token-2");
        guest.addItem(1L, 10L, 2);   // 옵션10 ×2
        guest.addItem(2L, 20L, 1);   // 옵션20 ×1
        cartRepository.saveAndFlush(guest);
        Cart member = Cart.create(100L);
        member.addItem(1L, 10L, 1);  // 옵션10 ×1 (겹침)
        cartRepository.saveAndFlush(member);

        cartService.mergeGuestIntoMember("g-token-2", 100L);

        Cart merged = cartRepository.findByMemberId(100L).orElseThrow();
        assertThat(qty(merged, 10L)).isEqualTo(3);   // 1 + 2 합산
        assertThat(qty(merged, 20L)).isEqualTo(1);
        assertThat(cartRepository.findByCartToken("g-token-2")).isEmpty();   // 게스트 카트 삭제됨
    }

    private int qty(Cart cart, long optionId) {
        return cart.getCartItems().stream()
                .filter(i -> i.getOptionId().equals(optionId)).findFirst().orElseThrow().getQuantity();
    }
}
