package com.commerce.api.review.repository;

import com.commerce.api.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 리뷰 DB 접근. 동적 검색(평점·사진 필터)·평점 분포는 {@link ReviewRepositoryCustom}(QueryDSL).
 */
public interface ReviewRepository extends JpaRepository<Review, Long>, ReviewRepositoryCustom {

    /** 1인 1상품 1리뷰 검증용 — 이미 이 회원이 이 상품에 리뷰를 썼는지. */
    boolean existsByMemberIdAndProductId(Long memberId, Long productId);

    /** 특정 상품의 리뷰 목록(페이지). 정렬·크기는 Pageable에 따름. */
    Page<Review> findByProductId(Long productId, Pageable pageable);
}
