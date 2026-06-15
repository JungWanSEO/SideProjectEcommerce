package com.commerce.api.recommendation.entity;

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
 * "나를 위한 추천" 결과 한 줄 (recommendation 테이블) — 배치가 회원별로 미리 계산해 저장(precomputed).
 *
 * <ul>
 *   <li>회원·상품은 다른 애그리거트 → <b>ID 참조</b>(memberId/productId), FK 없음.
 *   <li><b>(member_id, product_id) UNIQUE</b>: 한 회원에게 같은 상품은 한 줄. 배치는 회원별로 지우고 다시 넣는다.
 *   <li>순위는 <b>score 내림차순</b>으로 읽어 정한다(별도 rank 컬럼 없음 — {@code rank}는 MySQL 예약어라 회피).
 * </ul>
 *
 * <p>읽기(GET /me)는 매번 추천을 계산하지 않고 이 테이블을 정렬 조회만 한다 — 정산의 "미리 계산해 저장"과 같은 발상.
 */
@Getter
@Entity
@Table(name = "recommendation", uniqueConstraints = @UniqueConstraint(
        name = "uk_recommendation_member_product", columnNames = {"member_id", "product_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;     // 추천 대상 회원 → ID 참조

    @Column(nullable = false)
    private Long productId;    // 추천 상품 → ID 참조

    /** 추천 점수(친화도 + 인기도). 클수록 상위. 점수는 비율/가중합이라 double(돈 아님). */
    @Column(nullable = false)
    private double score;

    @Builder
    private Recommendation(Long memberId, Long productId, double score) {
        this.memberId = memberId;
        this.productId = productId;
        this.score = score;
    }

    public static Recommendation of(Long memberId, Long productId, double score) {
        return Recommendation.builder().memberId(memberId).productId(productId).score(score).build();
    }
}
