package com.commerce.api.activity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * ActivityLogService 단위 테스트 (Mockito). 조회 기록 / 없는 상품 404.
 */
@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Mock private ActivityLogRepository activityLogRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private ActivityLogService activityLogService;

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 7L;

    @Test
    @DisplayName("조회 기록 성공 - 상품이 존재하면 VIEW 로그 1건 저장")
    void logView_success() {
        given(productRepository.existsById(PRODUCT_ID)).willReturn(true);

        activityLogService.logView(MEMBER_ID, PRODUCT_ID);

        ArgumentCaptor<ActivityLog> captor = ArgumentCaptor.forClass(ActivityLog.class);
        verify(activityLogRepository).save(captor.capture());
        ActivityLog saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        org.assertj.core.api.Assertions.assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    @DisplayName("조회 기록 실패 - 없는 상품이면 404, 저장 안 함")
    void logView_productNotFound() {
        given(productRepository.existsById(PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> activityLogService.logView(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(activityLogRepository, never()).save(any());
    }
}
