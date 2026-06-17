package com.commerce.api.review.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.review.dto.ReviewCreateRequest;
import com.commerce.api.review.dto.ReviewResponse;
import com.commerce.api.review.entity.Review;
import com.commerce.api.review.repository.ReviewRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 비즈니스 로직. 작성 / 목록 / 삭제.
 *
 * <p>핵심 정책:
 * <ul>
 *   <li><b>구매자만 작성</b>: 해당 상품이 들어간 PAID 주문이 있어야 한다(review → order ID 참조 검증).
 *   <li><b>1인 1상품 1리뷰</b>: 중복이면 409.
 *   <li><b>평점 집계</b>: Product의 비정규화 카운터를 <b>원자 UPDATE</b>로 증감(엔티티 더티체킹 대신 — 동시 작성 시
 *       lost update 방지, Product에 @Version 없이도 안전).
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;

    /** 리뷰 작성: 상품 존재 → 구매(PAID) 검증 → 중복 검증 → 저장 + 상품 평점 카운터 증가. */
    @Transactional
    public ReviewResponse create(Long memberId, Long productId, ReviewCreateRequest request) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        // 구매자만: 이 회원이 이 상품을 구매(결제~배송완료) 주문으로 산 적이 있어야 한다.
        boolean purchased = orderRepository.existsByMemberIdAndStatusInAndOrderItems_ProductId(
                memberId, OrderStatus.PURCHASED, productId);
        if (!purchased) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "구매한 상품에만 리뷰를 작성할 수 있습니다.");
        }
        if (reviewRepository.existsByMemberIdAndProductId(memberId, productId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 이 상품에 리뷰를 작성했습니다.");
        }

        Review saved = reviewRepository.save(Review.builder()
                .memberId(memberId)
                .productId(productId)
                .rating(request.rating())
                .content(request.content())
                .imageUrl(request.imageUrl())
                .build());

        // 상품 평점 집계 갱신(원자 UPDATE) — 평균은 읽을 때 sum/count로 계산.
        productRepository.incrementRating(productId, request.rating());

        return ReviewResponse.of(saved, writerName(memberId));
    }

    /** 특정 상품의 리뷰 목록(페이지). 공개 — 작성자 닉네임은 한 번에 모아 enrich(N+1 회피). */
    public PageResponse<ReviewResponse> getReviews(Long productId, Pageable pageable) {
        Page<Review> page = reviewRepository.findByProductId(productId, pageable);
        Map<Long, String> writerNames = writerNames(page.getContent());
        return PageResponse.from(page.map(r -> ReviewResponse.of(r, writerNames.get(r.getMemberId()))));
    }

    /** 리뷰 삭제: 본인 또는 ADMIN만(아니면 403). 삭제 시 상품 평점 카운터도 감소. */
    @Transactional
    public void delete(Long reviewId, Long memberId, boolean admin) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."));
        if (!admin && !review.getMemberId().equals(memberId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인 리뷰만 삭제할 수 있습니다.");
        }
        reviewRepository.delete(review);
        productRepository.decrementRating(review.getProductId(), review.getRating());
    }

    /**
     * 리뷰 수정: 작성자 <b>본인만</b>(아니면 403). 평점이 바뀌면 상품 평점 합계를 델타만큼 조정(개수는 그대로).
     * (삭제는 ADMIN도 가능하지만 내용 수정은 작성자만 — 남의 글을 고쳐 쓰지 않는다.)
     */
    @Transactional
    public ReviewResponse update(Long reviewId, Long memberId, ReviewCreateRequest request) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."));
        if (!review.getMemberId().equals(memberId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인 리뷰만 수정할 수 있습니다.");
        }
        int delta = request.rating() - review.getRating();
        review.update(request.rating(), request.content(), request.imageUrl());   // 더티 체킹으로 UPDATE
        if (delta != 0) {
            productRepository.adjustRatingSum(review.getProductId(), delta);       // 평점 변화만 합계 반영
        }
        return ReviewResponse.of(review, writerName(memberId));
    }

    // --- writerName enrich: memberId로 닉네임 채우기 (상품 enrich와 같은 발상) ---

    /** 단건: 회원 닉네임(없으면 null). */
    private String writerName(Long memberId) {
        return memberRepository.findById(memberId).map(Member::getNickname).orElse(null);
    }

    /** 목록: 리뷰들의 memberId를 모아 한 번에 조회 → {id: nickname} 맵. */
    private Map<Long, String> writerNames(List<Review> reviews) {
        Set<Long> ids = reviews.stream().map(Review::getMemberId).collect(Collectors.toSet());
        return memberRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Member::getId, Member::getNickname));
    }
}
