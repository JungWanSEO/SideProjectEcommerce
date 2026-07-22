package com.commerce.api.product.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.entity.StockReservation;
import com.commerce.api.product.entity.StockReservationStatus;
import com.commerce.api.product.repository.ProductOptionRepository;
import com.commerce.api.product.repository.StockReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 예약 오케스트레이터(#2) — 주문 생명주기에서 예약/소진/해제를 원자적으로 처리한다.
 *
 * <p>모두 호출자(주문 생성·결제·취소·만료)의 트랜잭션에 합류한다({@code REQUIRED}) — 주문이 롤백되면
 * 예약도 함께 롤백돼야 정합하기 때문. reserved 카운터 증감은 {@link ProductOptionRepository}의
 * 원자적 조건부 UPDATE가, "누가 무엇을 얼마나" 기록은 {@link StockReservation} 행이 담당한다.
 */
@Service
@RequiredArgsConstructor
public class StockReservationService {

    private final ProductOptionRepository productOptionRepository;
    private final StockReservationRepository stockReservationRepository;

    /**
     * 예약(주문 생성 시). 가용재고가 충분하면 reserved를 원자적으로 늘리고 예약 행(ACTIVE)을 남긴다.
     * 부족하면 <b>409 품절</b>로 주문 생성 자체를 실패시킨다(오버셀 차단은 여기서 일어난다).
     */
    @Transactional
    public void reserve(Long orderId, Long orderItemId, Long optionId, int quantity, LocalDateTime expiresAt) {
        int updated = productOptionRepository.reserve(optionId, quantity);
        if (updated == 0) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "재고가 부족합니다. (옵션 id: " + optionId + ", 요청 수량: " + quantity + ")");
        }
        stockReservationRepository.save(
                StockReservation.active(orderId, orderItemId, optionId, quantity, expiresAt));
    }

    /** 소진(결제 확정 시). 이 주문의 ACTIVE 예약을 전부 실재고 차감으로 전환하고 CONSUMED로 마감. */
    @Transactional
    public void consumeForOrder(Long orderId) {
        for (StockReservation r : activeOf(orderId)) {
            productOptionRepository.consume(r.getOptionId(), r.getQuantity());
            r.markConsumed();   // 영속 엔티티 → dirty checking flush
        }
    }

    /** 해제(주문 만료·전체 취소 시). 이 주문의 ACTIVE 예약을 전부 되돌리고 RELEASED로 마감. */
    @Transactional
    public void releaseForOrder(Long orderId) {
        for (StockReservation r : activeOf(orderId)) {
            productOptionRepository.release(r.getOptionId(), r.getQuantity());
            r.markReleased();
        }
    }

    /** 해제(항목 단위 취소 시). 이 주문 항목의 ACTIVE 예약만 정확히 되돌린다(PENDING 부분취소용). */
    @Transactional
    public void releaseForOrderItem(Long orderItemId) {
        for (StockReservation r : stockReservationRepository
                .findByOrderItemIdAndStatus(orderItemId, StockReservationStatus.ACTIVE)) {
            productOptionRepository.release(r.getOptionId(), r.getQuantity());
            r.markReleased();
        }
    }

    private List<StockReservation> activeOf(Long orderId) {
        return stockReservationRepository.findByOrderIdAndStatus(orderId, StockReservationStatus.ACTIVE);
    }
}
