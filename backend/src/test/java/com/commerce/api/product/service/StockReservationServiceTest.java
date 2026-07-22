package com.commerce.api.product.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.entity.StockReservation;
import com.commerce.api.product.entity.StockReservationStatus;
import com.commerce.api.product.repository.ProductOptionRepository;
import com.commerce.api.product.repository.StockReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * StockReservationService 단위 테스트(#2) — 예약/소진/해제의 원자 UPDATE 위임 + 상태 전이.
 */
@ExtendWith(MockitoExtension.class)
class StockReservationServiceTest {

    @Mock private ProductOptionRepository productOptionRepository;
    @Mock private StockReservationRepository stockReservationRepository;

    @InjectMocks private StockReservationService stockReservationService;

    private static final LocalDateTime EXP = LocalDateTime.now().plusMinutes(30);

    @Test
    @DisplayName("예약 성공 - 가용재고 충분(reserve=1)이면 예약 행을 남긴다")
    void reserve_success() {
        given(productOptionRepository.reserve(10L, 2)).willReturn(1);

        stockReservationService.reserve(1L, 500L, 10L, 2, EXP);

        verify(stockReservationRepository).save(any(StockReservation.class));
    }

    @Test
    @DisplayName("예약 실패 - 가용재고 부족(reserve=0)이면 409, 예약 행 없음")
    void reserve_soldOut_409() {
        given(productOptionRepository.reserve(10L, 2)).willReturn(0);

        assertThatThrownBy(() -> stockReservationService.reserve(1L, 500L, 10L, 2, EXP))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(stockReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("소진(결제) - 주문의 ACTIVE 예약을 실재고 차감으로 전환하고 CONSUMED로 마감")
    void consumeForOrder() {
        StockReservation r1 = StockReservation.active(1L, 500L, 10L, 2, EXP);
        StockReservation r2 = StockReservation.active(1L, 501L, 20L, 3, EXP);
        given(stockReservationRepository.findByOrderIdAndStatus(1L, StockReservationStatus.ACTIVE))
                .willReturn(List.of(r1, r2));

        stockReservationService.consumeForOrder(1L);

        verify(productOptionRepository).consume(10L, 2);
        verify(productOptionRepository).consume(20L, 3);
        Assertions.assertThat(r1.getStatus()).isEqualTo(StockReservationStatus.CONSUMED);
        Assertions.assertThat(r2.getStatus()).isEqualTo(StockReservationStatus.CONSUMED);
    }

    @Test
    @DisplayName("해제(전체취소·만료) - 주문의 ACTIVE 예약을 되돌리고 RELEASED로 마감")
    void releaseForOrder() {
        StockReservation r = StockReservation.active(1L, 500L, 10L, 2, EXP);
        given(stockReservationRepository.findByOrderIdAndStatus(1L, StockReservationStatus.ACTIVE))
                .willReturn(List.of(r));

        stockReservationService.releaseForOrder(1L);

        verify(productOptionRepository).release(10L, 2);
        Assertions.assertThat(r.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("해제(항목취소) - 그 항목의 ACTIVE 예약만 되돌린다")
    void releaseForOrderItem() {
        StockReservation r = StockReservation.active(1L, 500L, 10L, 2, EXP);
        given(stockReservationRepository.findByOrderItemIdAndStatus(500L, StockReservationStatus.ACTIVE))
                .willReturn(List.of(r));

        stockReservationService.releaseForOrderItem(500L);

        verify(productOptionRepository).release(10L, 2);
        Assertions.assertThat(r.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
    }
}
