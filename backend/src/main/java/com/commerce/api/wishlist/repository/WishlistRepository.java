package com.commerce.api.wishlist.repository;

import com.commerce.api.wishlist.entity.Wishlist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 위시리스트(찜) DB 접근.
 */
public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    /** 1인 1상품 1찜 검증용 — 이 회원이 이 상품을 이미 찜했는지(중복 추가 방지). */
    boolean existsByMemberIdAndProductId(Long memberId, Long productId);

    /** 찜 해제용 — 이 회원의 이 상품 찜 행을 찾는다(없으면 "찜하지 않은 상품"으로 404). */
    Optional<Wishlist> findByMemberIdAndProductId(Long memberId, Long productId);

    /** 내 찜 목록(페이지). 정렬·크기는 Pageable에 따름(기본 최신순). */
    Page<Wishlist> findByMemberId(Long memberId, Pageable pageable);

    /**
     * 내가 찜한 상품 ID 전체(페이지 없이). FE가 상품 목록/상세에서 하트를 채울 때 사용 —
     * 상품마다 찜 여부를 따로 묻지 않고, 내 찜 상품 ID 집합을 한 번에 받아 클라이언트가 대조한다.
     */
    @Query("select w.productId from Wishlist w where w.memberId = :memberId")
    List<Long> findProductIdsByMemberId(@Param("memberId") Long memberId);
}
