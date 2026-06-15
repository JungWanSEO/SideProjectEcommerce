package com.commerce.api.wishlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import com.commerce.api.wishlist.dto.WishlistResponse;
import com.commerce.api.wishlist.entity.Wishlist;
import com.commerce.api.wishlist.repository.WishlistRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WishlistService 단위 테스트 (Mockito). 상품 존재 / 중복 / 찜 카운터 증감 / 해제 검증.
 */
@ExtendWith(MockitoExtension.class)
class WishlistServiceTest {

    @Mock private WishlistRepository wishlistRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductService productService;

    @InjectMocks private WishlistService wishlistService;

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 7L;

    private Wishlist wishlistWithId(Long id, Long memberId, Long productId) {
        Wishlist wishlist = Wishlist.builder().memberId(memberId).productId(productId).build();
        ReflectionTestUtils.setField(wishlist, "id", id);
        return wishlist;
    }

    private ProductResponse productStub(Long id) {
        return new ProductResponse(id, "반팔티셔츠", 29000L, "면 100%", "/products/1.svg",
                ProductStatus.ON_SALE, 1L, "상의", 1L, "Nike",
                List.of(), 0, 0.0, 0, LocalDateTime.now());
    }

    @Test
    @DisplayName("찜 추가 성공 - 상품 존재·중복 없음 → 저장 + 찜 카운터 증가")
    void add_success() {
        given(productRepository.existsById(PRODUCT_ID)).willReturn(true);
        given(wishlistRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(false);
        given(wishlistRepository.save(any(Wishlist.class)))
                .willReturn(wishlistWithId(100L, MEMBER_ID, PRODUCT_ID));
        given(productService.getProductMap(anyCollection()))
                .willReturn(Map.of(PRODUCT_ID, productStub(PRODUCT_ID)));

        WishlistResponse response = wishlistService.add(MEMBER_ID, PRODUCT_ID);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.productId()).isEqualTo(PRODUCT_ID);
        assertThat(response.product().name()).isEqualTo("반팔티셔츠");
        verify(productRepository).incrementWishlist(PRODUCT_ID);   // 인기도 카운터 +1
    }

    @Test
    @DisplayName("찜 추가 실패 - 없는 상품이면 404, 저장·카운터 갱신 안 함")
    void add_productNotFound() {
        given(productRepository.existsById(PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> wishlistService.add(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(wishlistRepository, never()).save(any());
        verify(productRepository, never()).incrementWishlist(any());
    }

    @Test
    @DisplayName("찜 추가 실패 - 이미 찜한 상품이면 409, 저장·카운터 갱신 안 함")
    void add_duplicate() {
        given(productRepository.existsById(PRODUCT_ID)).willReturn(true);
        given(wishlistRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(true);

        assertThatThrownBy(() -> wishlistService.add(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(wishlistRepository, never()).save(any());
        verify(productRepository, never()).incrementWishlist(any());
    }

    @Test
    @DisplayName("찜 해제 성공 - 내 찜 행이 있으면 삭제 + 찜 카운터 감소")
    void remove_success() {
        Wishlist wishlist = wishlistWithId(100L, MEMBER_ID, PRODUCT_ID);
        given(wishlistRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
                .willReturn(Optional.of(wishlist));

        wishlistService.remove(MEMBER_ID, PRODUCT_ID);

        verify(wishlistRepository).delete(wishlist);
        verify(productRepository).decrementWishlist(PRODUCT_ID);
    }

    @Test
    @DisplayName("찜 해제 실패 - 찜하지 않은 상품이면 404, 삭제·카운터 갱신 안 함")
    void remove_notWishlisted() {
        given(wishlistRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> wishlistService.remove(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(wishlistRepository, never()).delete(any());
        verify(productRepository, never()).decrementWishlist(any());
    }
}
