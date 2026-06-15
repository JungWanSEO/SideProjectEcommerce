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
 * "함께 산 상품" 한 줄 (product_cooccurrence 테이블) — 배치가 기준 상품별로 미리 계산해 저장(precomputed).
 *
 * <ul>
 *   <li>기준 상품·추천 상품 모두 다른 애그리거트 → <b>ID 참조</b>(referenceProductId/productId), FK 없음.
 *   <li><b>(reference_product_id, product_id) UNIQUE</b>: 한 기준 상품에 같은 추천 상품은 한 줄.
 *       배치는 전체를 지우고 다시 넣는다(상품↔상품 전역 재계산).
 *   <li>순위는 <b>score 내림차순</b>으로 읽어 정한다(별도 rank 컬럼 없음 — Recommendation과 동일 발상).
 * </ul>
 *
 * <p>회원별 "나를 위한 추천"({@link Recommendation})과 형태(precompute 후 정렬 조회)는 같지만,
 * 이건 <b>회원 무관 상품↔상품</b> 통계라 테이블·키가 다르다.
 */
@Getter
@Entity
@Table(name = "product_cooccurrence", uniqueConstraints = @UniqueConstraint(
        name = "uk_cooccurrence_ref_product", columnNames = {"reference_product_id", "product_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductCoOccurrence extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long referenceProductId;   // 상세 페이지의 기준 상품 → ID 참조

    @Column(nullable = false)
    private Long productId;            // 함께 산(추천) 상품 → ID 참조

    /** 함께 담긴 서로 다른 PAID 주문 수(COUNT DISTINCT order). 신호 강도 — 클수록 강하게 함께 팔림. */
    @Column(nullable = false)
    private int coBuyCount;

    /** 추천 점수 = 함께 산 횟수 가중 + 인기도 타이브레이크. 클수록 상위. 비율/가중합이라 double(돈 아님). */
    @Column(nullable = false)
    private double score;

    @Builder
    private ProductCoOccurrence(Long referenceProductId, Long productId, int coBuyCount, double score) {
        this.referenceProductId = referenceProductId;
        this.productId = productId;
        this.coBuyCount = coBuyCount;
        this.score = score;
    }

    public static ProductCoOccurrence of(Long referenceProductId, Long productId, int coBuyCount, double score) {
        return ProductCoOccurrence.builder()
                .referenceProductId(referenceProductId)
                .productId(productId)
                .coBuyCount(coBuyCount)
                .score(score)
                .build();
    }
}
