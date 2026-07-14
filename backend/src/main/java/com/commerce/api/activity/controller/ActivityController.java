package com.commerce.api.activity.controller;

import com.commerce.api.activity.dto.ActivityViewRequest;
import com.commerce.api.activity.service.ActivityLogService;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.product.dto.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 행동 로그 API. <b>로그인 필요</b>(SecurityConfig의 공개·ADMIN 규칙에 없어 {@code anyRequest().authenticated()}).
 *
 * <ul>
 *   <li>POST /api/activity/views            상품 조회 1건 기록 (개인화 추천의 입력 신호)
 *   <li>GET  /api/activity/recently-viewed  최근 본 상품 (내 조회 로그를 상품별 최신 1건으로 접어 최신순)
 * </ul>
 *
 * <p>조회는 GET(상품 상세)에 부수효과로 끼우지 않고 <b>전용 POST</b>로 분리한다 — 읽기(GET)는 부수효과 없이.
 * FE가 상세 진입 시(로그인 상태) 이 엔드포인트를 가볍게 호출한다(best-effort, 실패해도 화면엔 영향 없음).
 *
 * <p>"최근 본 상품"은 상품 목록을 돌려주지만 <b>product가 아니라 여기(activity)</b>에 둔다 — 데이터 출처가
 * activity_log이고, 반대로 두면 product → activity 역방향 의존이 생긴다("함께 산 상품"을 ProductController가
 * 아니라 recommendation에 둔 것과 같은 이유). 덤으로 공개(permitAll)인 {@code /api/products/**} GET 매처와
 * 섞이지 않아 인가 규칙이 단순해진다.
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

    @Operation(summary = "최근 본 상품 조회",
            description = "내가 최근 본 상품을 최신순으로 반환한다(같은 상품을 여러 번 봐도 1건). 로그인 필요. "
                    + "판매중지·삭제된 상품은 제외하며, exclude로 지금 보고 있는 상품을 뺄 수 있다. "
                    + "이력이 없으면 빈 목록(추천과 달리 인기순 폴백 없음).")
    @GetMapping("/recently-viewed")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> recentlyViewed(
            @RequestParam(defaultValue = "8") int limit,
            @RequestParam(required = false) Long exclude) {
        List<ProductResponse> products = activityLogService.getRecentlyViewed(
                SecurityUtil.getCurrentMemberId(), limit, exclude);
        return ResponseEntity.ok(ApiResponse.success(products));
    }
}
