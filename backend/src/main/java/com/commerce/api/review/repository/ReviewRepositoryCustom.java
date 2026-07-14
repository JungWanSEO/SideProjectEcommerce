package com.commerce.api.review.repository;

import com.commerce.api.review.dto.ReviewSearchCondition;
import com.commerce.api.review.entity.Review;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 리뷰 동적 검색·집계(QueryDSL). 구현은 {@link ReviewRepositoryImpl}(Spring Data 이름 규칙).
 */
public interface ReviewRepositoryCustom {

    /** 상품 리뷰를 조건(평점·사진 유무)으로 걸러 페이지 조회한다. 정렬은 Pageable(createdAt·rating). */
    Page<Review> search(Long productId, ReviewSearchCondition condition, Pageable pageable);

    /**
     * 평점 분포 — {@code group by rating}. 없는 별점은 행 자체가 없으니 서비스가 0으로 채운다.
     *
     * @return {@code [rating(Integer), count(Long)]} 배열 목록
     */
    List<Object[]> countGroupByRating(Long productId);
}
