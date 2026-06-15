package com.commerce.api.recommendation.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.recommendation.dto.RecommendationResponse;
import com.commerce.api.recommendation.service.RecommendationBatchService;
import com.commerce.api.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 추천 API.
 * <ul>
 *   <li>GET  /api/recommendations/me   내 추천 (로그인 — 이력 기반, 없으면 인기순 폴백)
 *   <li>POST /api/recommendations/run  추천 배치 수동 재계산 (ADMIN — 데모/검증용. 평소엔 @Scheduled 자동)
 * </ul>
 *
 * <p>경로 인가: {@code /me}는 {@code anyRequest().authenticated()}로 로그인 필요,
 * {@code /run}은 SecurityConfig에서 ADMIN으로 제한.
 */
@Tag(name = "추천(Recommendation)", description = "나를 위한 추천 조회 / 배치 재계산 API")
@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final RecommendationBatchService recommendationBatchService;

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
}
