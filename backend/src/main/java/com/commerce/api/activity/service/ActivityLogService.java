package com.commerce.api.activity.service;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행동 로그 비즈니스 로직. 상품 조회 기록.
 *
 * <p>조회는 빈도가 높아 가볍게 — 상품 존재만 확인하고 append. (로그인 사용자만: memberId는 컨트롤러가
 * 인증 컨텍스트에서 넘겨준다. 개인화 추천의 입력이라 익명 조회는 받지 않는다.)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final ProductRepository productRepository;

    /** 상품 조회 1건 기록. 없는 상품이면 404. */
    @Transactional
    public void logView(Long memberId, Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        activityLogRepository.save(ActivityLog.view(memberId, productId));
    }
}
