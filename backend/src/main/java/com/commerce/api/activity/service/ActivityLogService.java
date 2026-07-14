package com.commerce.api.activity.service;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.entity.ActivityType;
import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.product.service.ProductService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행동 로그 비즈니스 로직. 상품 조회 기록 + 최근 본 상품 조회.
 *
 * <p>조회는 빈도가 높아 가볍게 — 상품 존재만 확인하고 append. (로그인 사용자만: memberId는 컨트롤러가
 * 인증 컨텍스트에서 넘겨준다. 개인화 추천의 입력이라 익명 조회는 받지 않는다.)
 *
 * <p>상품 enrich(이름·가격·이미지)는 {@link ProductService#getProductMap}에 위임한다(추천 도메인과 동일).
 * 의존 방향은 activity → product 한 방향(순환 없음).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityLogService {

    /** 최근 본 상품 최대 개수(요청이 더 크게 와도 여기서 자른다). */
    private static final int MAX_RECENT_LIMIT = 20;

    /**
     * 후보를 요청 개수의 몇 배까지 넉넉히 뽑을지. 조회 로그엔 그 뒤 <b>판매중지·삭제된 상품</b>도 남아 있어
     * 그대로 limit개만 뽑으면 걸러진 만큼 레일이 비어 보인다. 넉넉히 뽑아 거른 뒤 limit개로 자른다.
     */
    private static final int CANDIDATE_MULTIPLIER = 3;

    private final ActivityLogRepository activityLogRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    /** 상품 조회 1건 기록. 없는 상품이면 404. */
    @Transactional
    public void logView(Long memberId, Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        activityLogRepository.save(ActivityLog.view(memberId, productId));
    }

    /**
     * 최근 본 상품 (최신순·상품별 1건). 조회 이력이 없으면 빈 목록(폴백 없음 — "최근 본"은 없으면 없는 것).
     *
     * @param excludeProductId 결과에서 뺄 상품(상품 상세에서 "지금 보고 있는 상품"을 제외). null이면 제외 없음.
     */
    public List<ProductResponse> getRecentlyViewed(Long memberId, int limit, Long excludeProductId) {
        int size = Math.min(Math.max(limit, 1), MAX_RECENT_LIMIT);
        List<Long> productIds = activityLogRepository.findRecentlyViewedProductIds(
                memberId, ActivityType.VIEW, PageRequest.of(0, size * CANDIDATE_MULTIPLIER));

        Map<Long, ProductResponse> products = productService.getProductMap(productIds);
        return productIds.stream()
                .filter(id -> !id.equals(excludeProductId))
                .map(products::get)
                .filter(Objects::nonNull)                                  // 그 사이 삭제된 상품
                .filter(p -> p.status() != ProductStatus.DISCONTINUED)     // 판매중지 상품은 레일에서 뺀다
                .limit(size)
                .toList();
    }
}
