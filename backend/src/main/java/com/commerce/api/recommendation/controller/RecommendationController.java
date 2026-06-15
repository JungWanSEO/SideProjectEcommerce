package com.commerce.api.recommendation.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.recommendation.dto.CoOccurrenceResponse;
import com.commerce.api.recommendation.dto.RecommendationResponse;
import com.commerce.api.recommendation.service.CoOccurrenceBatchService;
import com.commerce.api.recommendation.service.CoOccurrenceService;
import com.commerce.api.recommendation.service.RecommendationBatchService;
import com.commerce.api.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 추천 API.
 * <ul>
 *   <li>GET  /api/recommendations/me                              내 추천 (로그인 — 이력 기반, 없으면 인기순 폴백)
 *   <li>POST /api/recommendations/run                             추천 배치 수동 재계산 (ADMIN — 평소엔 @Scheduled 자동)
 *   <li>GET  /api/recommendations/products/{id}/together          함께 산 상품 (공개 — 상품 통계, 없으면 카테고리/브랜드 폴백)
 *   <li>POST /api/recommendations/cooccurrence/run                함께 산 상품 배치 재계산 (ADMIN)
 * </ul>
 *
 * <p>경로 인가: {@code /me}는 {@code anyRequest().authenticated()}로 로그인 필요,
 * {@code /products/*​/together}는 공개(permitAll), {@code /run}·{@code /cooccurrence/run}은 ADMIN — SecurityConfig 참고.
 *
 * <p>"함께 산 상품"도 추천(precompute→정렬 조회→폴백)이라 이 도메인에 응집한다. 단 상품 enrich를 위해
 * recommendation→product로만 의존해야 하므로(순환 방지), 공개 읽기를 ProductController가 아니라 여기 둔다.
 */
@Tag(name = "추천(Recommendation)", description = "나를 위한 추천 / 함께 산 상품 조회 / 배치 재계산 API")
@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final RecommendationBatchService recommendationBatchService;
    private final CoOccurrenceService coOccurrenceService;
    private final CoOccurrenceBatchService coOccurrenceBatchService;

    @Operation(summary = "나를 위한 추천 조회",
            description = "로그인 사용자의 행동(구매·찜·조회) 기반 추천 상품을 반환한다. 이력이 없으면 "
                    + "전체 인기순으로 폴백하며 personalized=false로 표시한다.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<RecommendationResponse>> myRecommendations() {
        RecommendationResponse response =
                recommendationService.getMyRecommendations(SecurityUtil.getCurrentMemberId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "추천 배치 재계산 (ADMIN)",
            description = "모든 회원의 추천을 지금 다시 계산한다(평소엔 스케줄로 자동). 생성된 추천 수를 반환.")
    @PostMapping("/run")
    public ResponseEntity<ApiResponse<Integer>> run() {
        int created = recommendationBatchService.run();
        return ResponseEntity.ok(ApiResponse.success("추천을 재계산했습니다.", created));
    }

    @Operation(summary = "함께 산 상품 조회",
            description = "상품 상세: 이 상품을 산 사람들이 함께 산 상품을 반환한다(공개·로그인 불필요). "
                    + "함께 산 데이터가 없으면 같은 카테고리/브랜드 상품으로 폴백하며 cooccurrence=false로 표시한다.")
    @GetMapping("/products/{productId}/together")
    public ResponseEntity<ApiResponse<CoOccurrenceResponse>> together(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "8") int limit) {
        CoOccurrenceResponse response = coOccurrenceService.getCoOccurrence(productId, limit);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "함께 산 상품 배치 재계산 (ADMIN)",
            description = "모든 PAID 주문에서 함께 산 상품 쌍을 지금 다시 계산한다(평소엔 스케줄로 자동). 생성된 행 수를 반환.")
    @PostMapping("/cooccurrence/run")
    public ResponseEntity<ApiResponse<Integer>> runCoOccurrence() {
        int created = coOccurrenceBatchService.run();
        return ResponseEntity.ok(ApiResponse.success("함께 산 상품을 재계산했습니다.", created));
    }
}
