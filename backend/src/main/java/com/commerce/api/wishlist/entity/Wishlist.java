package com.commerce.api.wishlist.entity;

import com.commerce.api.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 위시리스트(찜) 항목 (wishlist 테이블).
 *
 * <p>구조는 {@code Review}와 쌍둥이 — 회원이 상품을 찜한 사실 하나를 한 행으로 남긴다.
 * <ul>
 *   <li>회원·상품은 다른 애그리거트 → <b>ID 참조</b>(memberId/productId), FK 제약 없음(architecture.md §11).
 *   <li><b>1인 1상품 1찜</b>: (member_id, product_id) UNIQUE — 같은 상품을 두 번 찜할 수 없다.
 *   <li>찜 자체는 토글성 데이터라 별도 상태 컬럼이 없다(있으면 찜, 없으면 안 찜). createdAt(BaseEntity)으로 최신순 정렬.
 *   <li>인기도(찜 수)는 여기서 집계하지 않는다 — Product의 비정규화 카운터(wishlistCount)를 추가/해제 시 원자 UPDATE.
 * </ul>
 *
 * <p>.NET 비유: EF Core의 조인 엔티티(가벼운 다대다 연결 테이블)와 같은 역할이되,
 * 네비게이션 프로퍼티 대신 식별자(Long)만 들고 있어 애그리거트 경계를 넘지 않는다.
 */
@Getter
@Entity
@Table(name = "wishlist", uniqueConstraints = @UniqueConstraint(
        name = "uk_wishlist_member_product", columnNames = {"member_id", "product_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;     // 찜한 회원 → ID 참조

    @Column(nullable = false)
    private Long productId;    // 찜한 상품 → ID 참조

    @Builder
    private Wishlist(Long memberId, Long productId) {
        this.memberId = memberId;
        this.productId = productId;
    }
}
