package com.commerce.api.activity.controller;

import com.commerce.api.activity.dto.ActivityViewRequest;
import com.commerce.api.activity.service.ActivityLogService;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.security.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 행동 로그 API. <b>로그인 필요</b>(SecurityConfig의 공개·ADMIN 규칙에 없어 {@code anyRequest().authenticated()}).
 *
 * <ul>
 *   <li>POST /api/activity/views  상품 조회 1건 기록 (개인화 추천의 입력 신호)
 * </ul>
 *
 * <p>조회는 GET(상품 상세)에 부수효과로 끼우지 않고 <b>전용 POST</b>로 분리한다 — 읽기(GET)는 부수효과 없이.
 * FE가 상세 진입 시(로그인 상태) 이 엔드포인트를 가볍게 호출한다(best-effort, 실패해도 화면엔 영향 없음).
 */
@Tag(name = "행동 로그(Activity)", description = "개인화 추천용 행동(조회) 기록 API (로그인 필요)")
@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityLogService activityLogService;

    @Operation(summary = "상품 조회 기록",
            description = "회원이 상품을 봤다는 행동을 1건 기록한다(append). 로그인 필요, 없는 상품이면 404. "
                    + "추천 배치가 이 조회 신호를 찜·구매와 함께 가중 합산한다.")
    @PostMapping("/views")
    public ResponseEntity<ApiResponse<Void>> logView(@Valid @RequestBody ActivityViewRequest request) {
        activityLogService.logView(SecurityUtil.getCurrentMemberId(), request.productId());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.<Void>success("조회를 기록했습니다.", null));
    }
}
