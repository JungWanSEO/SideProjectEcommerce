package com.commerce.api.cart.repository;

import com.commerce.api.cart.entity.Cart;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 장바구니 DB 접근.
 */
public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByMemberId(Long memberId);

    /** 게스트 장바구니 조회(쿠키 토큰, #7). */
    Optional<Cart> findByCartToken(String cartToken);

    /**
     * 오래 방치된 <b>게스트</b> 장바구니 — TTL 정리 배치 대상(#7 후속).
     *
     * <p>{@code memberId is null}(=게스트)만 고른다. 회원 카트는 계정에 딸린 자산이라 만료 대상이 아니다.
     * 기준 시각은 {@code updatedAt} — 담기/수량 변경마다 갱신되므로 "마지막 활동 후 N일"이 된다
     * (createdAt이면 계속 쓰는 카트도 만료돼 버린다).
     */
    List<Cart> findByMemberIdIsNullAndUpdatedAtBefore(LocalDateTime threshold);
}