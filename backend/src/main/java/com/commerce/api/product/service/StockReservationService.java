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

    /**
     * 소진(결제 확정 시). 이 주문의 ACTIVE 예약을 전부 실재고 차감으로 전환하고 CONSUMED로 마감.
     *
     * <p>consume이 0행이면(예: 만료 배치가 먼저 예약을 해제해 reserved가 사라졌거나, 관리자가 재고를 예약분
     * 아래로 내림) 불변식 위반이므로 <b>409로 결제 트랜잭션을 롤백</b>한다 — "예약이 사라졌는데 결제만 확정"되는
     * 조용한 오버셀을 막는다(그 주문은 이미 만료/취소됐거나 재고가 소진된 것).
     */
    @Transactional
    public void consumeForOrder(Long orderId) {
        for (StockReservation r : activeOf(orderId)) {
            if (productOptionRepository.consume(r.getOptionId(), r.getQuantity()) == 0) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "예약이 만료/취소되어 결제할 수 없습니다. (옵션 id: " + r.getOptionId() + ")");
            }
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

    /**
     * 항목 취소 시 재고 되돌리기(#1 P4, trap#7 차단) — 그 항목의 <b>예약 상태로</b> 처리를 가른다.
     *   · CONSUMED(결제로 실차감됨) → 실재고 복원(restore)
     *   · ACTIVE(예약만, 미결제) → 예약 해제(release)
     *   · RELEASED(이미 처리) → no-op(멱등)
     *
     * <p>전체 Order.status가 아니라 항목별 실차감 여부로 판정하는 게 핵심 — 멀티셀러에서 다른 셀러가 출고해
     * 주문이 PAID를 벗어나도, 이 항목의 예약이 CONSUMED면 실재고가 정확히 복원된다(셀러 재고 영구누락 차단).
     */
    @Transactional
    public void undoForOrderItem(Long orderItemId) {
        for (StockReservation r : stockReservationRepository.findByOrderItemId(orderItemId)) {
            switch (r.getStatus()) {
                case CONSUMED -> {
                    productOptionRepository.restore(r.getOptionId(), r.getQuantity());
                    r.markReleased();   // 되돌림 완료 — 중복 복원 방지
                }
                case ACTIVE -> {
                    productOptionRepository.release(r.getOptionId(), r.getQuantity());
                    r.markReleased();
                }
                case RELEASED -> {
                    // 이미 되돌려짐 — 멱등 no-op
                }
            }
        }
    }

    /**
     * 교환(#3 P6) — 반환된 <b>원 옵션</b>의 실재고를 복원한다. undoForOrderItem은 항목의 모든 예약을 되돌려 옵션 스왑 후
     * 새 옵션까지 건드리므로, 원 optionId 예약(CONSUMED)만 골라 restore + RELEASED. 예약행이 없으면(레거시) 직접 복원.
     */
    @Transactional
    public void restoreOption(Long orderItemId, Long optionId, int quantity) {
        List<StockReservation> found = stockReservationRepository.findByOrderItemIdAndOptionId(orderItemId, optionId);
        if (found.isEmpty()) {
            productOptionRepository.restore(optionId, quantity);   // 레거시 폴백(예약행 없음)
            return;
        }
        for (StockReservation r : found) {
            if (r.getStatus() == StockReservationStatus.CONSUMED) {
                productOptionRepository.restore(optionId, quantity);
                r.markReleased();
            }
        }
    }

    /**
     * 교환(#3 P6) — 대체품(새 옵션) 재고를 예약→즉시 소진(실차감)하고 CONSUMED 예약 행을 남긴다(이후 재반품 시
     * undoForOrderItem이 이 행으로 새 옵션을 정확히 복원). 대체품 품절이면 409로 교환 확정을 롤백한다(자동 환불 전환 금지).
     */
    @Transactional
    public void consumeForExchange(Long orderId, Long orderItemId, Long optionId, int quantity, LocalDateTime expiresAt) {
        if (productOptionRepository.reserve(optionId, quantity) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "교환 대체품 재고가 부족합니다. (옵션 id: " + optionId + ")");
        }
        if (productOptionRepository.consume(optionId, quantity) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "교환 대체품 소진에 실패했습니다. (옵션 id: " + optionId + ")");
        }
        StockReservation res = StockReservation.active(orderId, orderItemId, optionId, quantity, expiresAt);
        res.markConsumed();   // 예약 즉시 실차감 전환 — 결제 없는 교환 출고
        stockReservationRepository.save(res);
    }

    private List<StockReservation> activeOf(Long orderId) {
        return stockReservationRepository.findByOrderIdAndStatus(orderId, StockReservationStatus.ACTIVE);
    }
}
