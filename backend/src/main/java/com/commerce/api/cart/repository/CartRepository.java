package com.commerce.api.cart.repository;

import com.commerce.api.cart.entity.Cart;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 장바구니 DB 접근.
 */
public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByMemberId(Long memberId);

    /** 게스트 장바구니 조회(쿠키 토큰, #7). */
    Optional<Cart> findByCartToken(String cartToken);
}