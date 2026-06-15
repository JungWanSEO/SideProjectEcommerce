package com.commerce.api.recommendation.repository;

import com.commerce.api.recommendation.entity.ProductCoOccurrence;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 함께 산 상품 결과 DB 접근.
 *
 * <p>전체 재계산(지우고-다시-넣기)은 배치가 {@link JpaRepository#deleteAllInBatch()}로 비운 뒤 saveAll 한다.
 */
public interface ProductCoOccurrenceRepository extends JpaRepository<ProductCoOccurrence, Long> {

    /**
     * 기준 상품의 "함께 산 상품" — 점수 내림차순(동점은 productId 오름차순으로 안정 정렬), 상위 N개(Pageable).
     */
    List<ProductCoOccurrence> findByReferenceProductIdOrderByScoreDescProductIdAsc(
            Long referenceProductId, Pageable pageable);
}
