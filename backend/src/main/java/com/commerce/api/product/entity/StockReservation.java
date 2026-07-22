package com.commerce.api.product.entity;

import com.commerce.api.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 예약 한 건 = (주문, 옵션)별로 잡아 둔 수량. 오버셀 방지를 위해 주문 생성 시점에 만든다.
 *
 * <p>애그리거트 경계상 order·option을 <b>ID 참조</b>(Long)로만 든다(다른 애그리거트 — DDD 원칙).
 * reserved 카운터의 실제 증감은 원자적 조건부 UPDATE(ProductOptionRepository)가 하고, 이 엔티티는
 * "어느 주문이 어느 옵션을 얼마나·언제까지 잡았는가"를 기록해 만료/취소 시 정확히 해제하게 한다.
 */
@Getter
@Entity
@Table(name = "stock_reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;       // 예약 주체 주문(ID 참조)

    @Column(nullable = false)
    private Long orderItemId;   // 예약 주체 주문 항목(ID 참조) — 항목 단위 취소 시 이 예약만 정확히 해제

    @Column(nullable = false)
    private Long optionId;      // 예약된 옵션(ID 참조) — reserved 카운터 원자 UPDATE 대상

    @Column(nullable = false)
    private int quantity;       // 예약 수량

    @Column(nullable = false)
    private LocalDateTime expiresAt;   // 이 시각 지나면 만료 대상(주문 만료 배치가 해제)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockReservationStatus status;

    private StockReservation(Long orderId, Long orderItemId, Long optionId, int quantity, LocalDateTime expiresAt) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.optionId = optionId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
        this.status = StockReservationStatus.ACTIVE;
    }

    /** 새 예약(ACTIVE) 생성. */
    public static StockReservation active(Long orderId, Long orderItemId, Long optionId,
            int quantity, LocalDateTime expiresAt) {
        return new StockReservation(orderId, orderItemId, optionId, quantity, expiresAt);
    }

    /** 결제 확정 → 실재고 차감으로 전환(예약 종료). */
    public void markConsumed() {
        this.status = StockReservationStatus.CONSUMED;
    }

    /** 만료·취소 → 해제(예약 종료). */
    public void markReleased() {
        this.status = StockReservationStatus.RELEASED;
    }

    public boolean isActive() {
        return this.status == StockReservationStatus.ACTIVE;
    }
}
