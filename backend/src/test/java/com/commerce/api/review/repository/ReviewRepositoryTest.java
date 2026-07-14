package com.commerce.api.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.config.JpaConfig;
import com.commerce.api.global.config.QuerydslConfig;
import com.commerce.api.review.dto.ReviewSearchCondition;
import com.commerce.api.review.entity.Review;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * ReviewRepository 슬라이스 테스트 — QueryDSL 동적 필터(평점·사진)·정렬·평점 분포.
 */
@DataJpaTest
@Import({JpaConfig.class, QuerydslConfig.class})
class ReviewRepositoryTest {

    private static final Long PRODUCT_ID = 7L;
    private static final Long OTHER_PRODUCT_ID = 8L;
    private static final ReviewSearchCondition NO_FILTER = new ReviewSearchCondition(null, null);

    @Autowired
    private ReviewRepository reviewRepository;

    private Review review(Long memberId, Long productId, int rating, String imageUrl) {
        return Review.builder()
                .memberId(memberId)
                .productId(productId)
                .rating(rating)
                .content("리뷰 내용")
                .imageUrl(imageUrl)
                .build();
    }

    @Test
    @DisplayName("search - 평점 필터: 그 별점 리뷰만 (다른 상품 리뷰는 섞이지 않는다)")
    void search_byRating() {
        reviewRepository.save(review(1L, PRODUCT_ID, 5, null));
        reviewRepository.save(review(2L, PRODUCT_ID, 3, null));
        reviewRepository.save(review(3L, OTHER_PRODUCT_ID, 5, null));   // 다른 상품

        Page<Review> page = reviewRepository.search(
                PRODUCT_ID, new ReviewSearchCondition(5, null), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Review::getMemberId).containsExactly(1L);
    }

    @Test
    @DisplayName("search - 사진리뷰만: imageUrl이 있는 리뷰만(빈 문자열도 제외)")
    void search_photoOnly() {
        reviewRepository.save(review(1L, PRODUCT_ID, 5, "https://img/1.jpg"));
        reviewRepository.save(review(2L, PRODUCT_ID, 4, null));
        reviewRepository.save(review(3L, PRODUCT_ID, 4, ""));   // 빈 문자열은 사진 없음으로 본다

        Page<Review> page = reviewRepository.search(
                PRODUCT_ID, new ReviewSearchCondition(null, true), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Review::getMemberId).containsExactly(1L);
    }

    @Test
    @DisplayName("search - 평점 높은순/낮은순 정렬 (Pageable의 sort=rating)")
    void search_sortByRating() {
        reviewRepository.save(review(1L, PRODUCT_ID, 3, null));
        reviewRepository.save(review(2L, PRODUCT_ID, 5, null));
        reviewRepository.save(review(3L, PRODUCT_ID, 1, null));

        Page<Review> desc = reviewRepository.search(PRODUCT_ID, NO_FILTER,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "rating")));
        Page<Review> asc = reviewRepository.search(PRODUCT_ID, NO_FILTER,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "rating")));

        assertThat(desc.getContent()).extracting(Review::getRating).containsExactly(5, 3, 1);
        assertThat(asc.getContent()).extracting(Review::getRating).containsExactly(1, 3, 5);
    }

    @Test
    @DisplayName("countGroupByRating - 별점별 개수(있는 별점만 행으로 나온다 → 0 채움은 서비스 몫)")
    void countGroupByRating() {
        reviewRepository.save(review(1L, PRODUCT_ID, 5, null));
        reviewRepository.save(review(2L, PRODUCT_ID, 5, null));
        reviewRepository.save(review(3L, PRODUCT_ID, 2, null));
        reviewRepository.save(review(4L, OTHER_PRODUCT_ID, 1, null));   // 다른 상품은 안 센다

        List<Object[]> rows = reviewRepository.countGroupByRating(PRODUCT_ID);

        assertThat(rows).hasSize(2);   // 5★·2★만 (4★·3★·1★은 행 없음)
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(5);
            assertThat(r[1]).isEqualTo(2L);
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(2);
            assertThat(r[1]).isEqualTo(1L);
        });
    }
}
