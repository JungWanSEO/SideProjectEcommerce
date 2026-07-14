package com.commerce.api.review.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.review.dto.ReviewCreateRequest;
import com.commerce.api.review.dto.ReviewResponse;
import com.commerce.api.review.dto.ReviewSearchCondition;
import com.commerce.api.review.dto.ReviewSummaryResponse;
import com.commerce.api.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 리뷰 API.
 * - POST   /api/products/{productId}/reviews  리뷰 작성 (인증 — 구매자만, 서비스에서 검증)
 * - GET    /api/products/{productId}/reviews  상품 리뷰 목록 (공개, 페이지)
 * - DELETE /api/reviews/{reviewId}            리뷰 삭제 (본인 또는 ADMIN)
 *
 * <p>경로 인가: GET은 SecurityConfig의 {@code GET /api/products/**} permitAll로 공개,
 * POST/DELETE는 매칭되는 공개·ADMIN 규칙이 없어 {@code anyRequest().authenticated()}로 인증 필요.
 */
@Tag(name = "리뷰(Review)", description = "상품 리뷰 작성 / 조회 / 삭제 API")
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "리뷰 작성",
            description = "상품에 평점(1~5)·내용·사진(선택)으로 리뷰를 단다. 로그인 필요하며 "
                    + "해당 상품을 구매(PAID)한 사용자만 가능(아니면 403). 같은 상품에 이미 썼으면 409.")
    @PostMapping("/api/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> create(
            @PathVariable Long productId,
            @Valid @RequestBody ReviewCreateRequest request) {
        ReviewResponse response = reviewService.create(
                SecurityUtil.getCurrentMemberId(), productId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("리뷰가 등록되었습니다.", response));
    }

    @Operation(summary = "상품 리뷰 목록 조회 (필터·정렬)",
            description = "특정 상품의 리뷰를 페이지로 조회한다(공개). 선택 필터: rating(그 별점만 1~5)·"
                    + "photoOnly(사진 있는 리뷰만). 정렬(sort): createdAt(최신), rating(평점 높은/낮은순). "
                    + "기본 최신순(createdAt desc), 기본 크기 10. 예: ?rating=5&sort=createdAt,desc")
    @GetMapping("/api/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> list(
            @PathVariable Long productId,
            @ParameterObject ReviewSearchCondition condition,
            @ParameterObject
            @PageableDefault(size = 10, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        PageResponse<ReviewResponse> response = reviewService.getReviews(productId, condition, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "상품 리뷰 평점 요약 (분포)",
            description = "별점 분포(5★~1★, 없는 별점은 0)·총 리뷰 수·평균을 반환한다(공개). "
                    + "평균은 분포에서 계산해 분포와 항상 일치한다.")
    @GetMapping("/api/products/{productId}/reviews/summary")
    public ResponseEntity<ApiResponse<ReviewSummaryResponse>> summary(@PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.success(reviewService.getSummary(productId)));
    }

    @Operation(summary = "리뷰 수정",
            description = "리뷰를 수정한다(평점·내용·사진). 작성자 본인만 가능(아니면 403). 없으면 404. "
                    + "평점이 바뀌면 상품 평점 평균이 갱신된다.")
    @PutMapping("/api/reviews/{reviewId}")
    public ResponseEntity<ApiResponse<ReviewResponse>> update(
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewCreateRequest request) {
        ReviewResponse response = reviewService.update(
                reviewId, SecurityUtil.getCurrentMemberId(), request);
        return ResponseEntity.ok(ApiResponse.success("리뷰가 수정되었습니다.", response));
    }

    @Operation(summary = "리뷰 삭제",
            description = "리뷰를 삭제한다. 본인 또는 ADMIN만 가능(아니면 403). 없으면 404. 삭제 시 상품 평점 집계도 감소.")
    @DeleteMapping("/api/reviews/{reviewId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long reviewId) {
        reviewService.delete(reviewId, SecurityUtil.getCurrentMemberId(), SecurityUtil.isAdmin());
        return ResponseEntity.ok(ApiResponse.<Void>success("리뷰가 삭제되었습니다.", null));
    }
}
