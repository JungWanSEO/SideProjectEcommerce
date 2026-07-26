package com.commerce.api.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.review.dto.ReviewCreateRequest;
import com.commerce.api.review.dto.ReviewResponse;
import com.commerce.api.review.dto.ReviewSummaryResponse;
import com.commerce.api.review.dto.ReviewSummaryResponse.RatingCount;
import com.commerce.api.review.entity.Review;
import com.commerce.api.review.repository.ReviewRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ReviewService 단위 테스트 (Mockito). 구매자 검증 / 중복 / 평점 카운터 증감 / 삭제 권한.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private ReviewRepository reviewRepository;
    @Mock private ProductRepository productRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private MemberRepository memberRepository;

    @InjectMocks private ReviewService reviewService;

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 7L;

    private Review reviewWithId(Long id, Long memberId, Long productId, int rating) {
        Review review = Review.builder()
                .memberId(memberId).productId(productId).rating(rating).content("좋아요").build();
        ReflectionTestUtils.setField(review, "id", id);
        return review;
    }

    private Member memberWithNickname(String nickname) {
        return Member.builder().email("a@b.com").password("x").nickname(nickname).role(Role.USER).build();
    }

    @Test
    @DisplayName("리뷰 작성 성공 - 구매(PAID)·중복 없음 → 저장 + 평점 카운터 증가")
    void create_success() {
        ReviewCreateRequest request = new ReviewCreateRequest(5, "핏이 좋아요", "/products/tee.svg");
        given(productRepository.existsById(PRODUCT_ID)).willReturn(true);
        given(orderRepository.hasActivePurchase(
                MEMBER_ID, OrderStatus.PURCHASED, PRODUCT_ID)).willReturn(true);
        given(reviewRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(false);
        given(reviewRepository.save(any(Review.class)))
                .willReturn(reviewWithId(100L, MEMBER_ID, PRODUCT_ID, 5));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(memberWithNickname("앨리스")));

        ReviewResponse response = reviewService.create(MEMBER_ID, PRODUCT_ID, request);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.writerName()).isEqualTo("앨리스");
        verify(productRepository).incrementRating(PRODUCT_ID, 5);   // 평점 집계 갱신
    }

    @Test
    @DisplayName("리뷰 작성 실패 - 구매 이력(PAID) 없으면 403, 저장·카운터 갱신 안 함")
    void create_notPurchased() {
        ReviewCreateRequest request = new ReviewCreateRequest(5, "핏이 좋아요", null);
        given(productRepository.existsById(PRODUCT_ID)).willReturn(true);
        given(orderRepository.hasActivePurchase(
                MEMBER_ID, OrderStatus.PURCHASED, PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> reviewService.create(MEMBER_ID, PRODUCT_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(reviewRepository, never()).save(any());
        verify(productRepository, never()).incrementRating(any(), anyInt());
    }

    @Test
    @DisplayName("리뷰 작성 실패 - 같은 상품에 이미 리뷰가 있으면 409")
    void create_duplicate() {
        ReviewCreateRequest request = new ReviewCreateRequest(4, "또 샀어요", null);
        given(productRepository.existsById(PRODUCT_ID)).willReturn(true);
        given(orderRepository.hasActivePurchase(
                MEMBER_ID, OrderStatus.PURCHASED, PRODUCT_ID)).willReturn(true);
        given(reviewRepository.existsByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(true);

        assertThatThrownBy(() -> reviewService.create(MEMBER_ID, PRODUCT_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("리뷰 작성 실패 - 없는 상품이면 404")
    void create_productNotFound() {
        ReviewCreateRequest request = new ReviewCreateRequest(5, "좋아요", null);
        given(productRepository.existsById(PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> reviewService.create(MEMBER_ID, PRODUCT_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("리뷰 수정 성공 - 본인 리뷰, 평점 5→3 → 내용 갱신 + 합계 델타(-2) 조정")
    void update_success() {
        Review review = reviewWithId(100L, MEMBER_ID, PRODUCT_ID, 5);
        given(reviewRepository.findById(100L)).willReturn(Optional.of(review));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(memberWithNickname("앨리스")));
        ReviewCreateRequest request = new ReviewCreateRequest(3, "다시 보니 보통이에요", null);

        ReviewResponse response = reviewService.update(100L, MEMBER_ID, request);

        assertThat(response.rating()).isEqualTo(3);
        assertThat(response.content()).isEqualTo("다시 보니 보통이에요");
        verify(productRepository).adjustRatingSum(PRODUCT_ID, -2);   // 5 → 3
    }

    @Test
    @DisplayName("리뷰 수정 - 평점이 그대로면 합계 조정은 호출하지 않는다")
    void update_sameRating_noAdjust() {
        Review review = reviewWithId(100L, MEMBER_ID, PRODUCT_ID, 4);
        given(reviewRepository.findById(100L)).willReturn(Optional.of(review));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(memberWithNickname("앨리스")));

        reviewService.update(100L, MEMBER_ID, new ReviewCreateRequest(4, "내용만 수정", null));

        verify(productRepository, never()).adjustRatingSum(any(), anyInt());
    }

    @Test
    @DisplayName("리뷰 수정 실패 - 남의 리뷰는 403, 합계 조정 안 함")
    void update_forbidden() {
        Review review = reviewWithId(100L, 999L, PRODUCT_ID, 5);   // 작성자 999, 요청자 1
        given(reviewRepository.findById(100L)).willReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.update(100L, MEMBER_ID, new ReviewCreateRequest(1, "악의수정", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(productRepository, never()).adjustRatingSum(any(), anyInt());
    }

    @Test
    @DisplayName("리뷰 삭제 성공 - 본인 리뷰 → 삭제 + 평점 카운터 감소")
    void delete_success() {
        Review review = reviewWithId(100L, MEMBER_ID, PRODUCT_ID, 5);
        given(reviewRepository.findById(100L)).willReturn(Optional.of(review));

        reviewService.delete(100L, MEMBER_ID, false);

        verify(reviewRepository).delete(review);
        verify(productRepository).decrementRating(PRODUCT_ID, 5);
    }

    @Test
    @DisplayName("리뷰 삭제 실패 - 남의 리뷰는 403(ADMIN 아님), 삭제·카운터 갱신 안 함")
    void delete_forbidden() {
        Review review = reviewWithId(100L, 999L, PRODUCT_ID, 5);   // 작성자 999, 요청자 1
        given(reviewRepository.findById(100L)).willReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.delete(100L, MEMBER_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(reviewRepository, never()).delete(any());
        verify(productRepository, never()).decrementRating(any(), anyInt());
    }

    // === 평점 분포 요약 ===

    @Test
    @DisplayName("평점 요약 - 없는 별점은 0으로 채워 항상 5행(5★→1★), 평균은 분포에서 계산")
    void getSummary_zeroFillsAndAverages() {
        // 5★ 두 개, 2★ 한 개 → 총 3개, 합 12 → 평균 4.0
        given(reviewRepository.countGroupByRating(PRODUCT_ID)).willReturn(List.of(
                new Object[]{5, 2L},
                new Object[]{2, 1L}));

        ReviewSummaryResponse summary = reviewService.getSummary(PRODUCT_ID);

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.average()).isEqualTo(4.0);   // (5+5+2)/3 = 4.0
        assertThat(summary.distribution()).extracting(RatingCount::rating)
                .containsExactly(5, 4, 3, 2, 1);        // 화면 막대가 늘 같은 모양이도록
        assertThat(summary.distribution()).extracting(RatingCount::count)
                .containsExactly(2L, 0L, 0L, 1L, 0L);
    }

    @Test
    @DisplayName("평점 요약 - 리뷰가 없으면 total 0·평균 0(0으로 나누지 않는다)")
    void getSummary_empty() {
        given(reviewRepository.countGroupByRating(PRODUCT_ID)).willReturn(List.of());

        ReviewSummaryResponse summary = reviewService.getSummary(PRODUCT_ID);

        assertThat(summary.total()).isZero();
        assertThat(summary.average()).isZero();
        assertThat(summary.distribution()).extracting(RatingCount::count)
                .containsExactly(0L, 0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("평점 요약 - 평균은 소수 1자리로 반올림(3.6666… → 3.7)")
    void getSummary_roundsAverage() {
        given(reviewRepository.countGroupByRating(PRODUCT_ID)).willReturn(List.of(
                new Object[]{5, 1L},
                new Object[]{4, 1L},
                new Object[]{2, 1L}));   // (5+4+2)/3 = 3.666…

        assertThat(reviewService.getSummary(PRODUCT_ID).average()).isEqualTo(3.7);
    }
}
