package com.commerce.api.order.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주문 항목 (Order 애그리거트 내부).
 *
 * - 상품은 ID 참조(productId, 다른 애그리거트).
 * - productName/orderPrice는 **주문 시점 스냅샷**. 이후 상품 정보가 바뀌어도 주문 내역은 보존된다.
 * - brandId/sellerId도 **주문 시점 스냅샷**(셀러별 정산용). 주문 후 상품의 브랜드가 바뀌거나
 *   브랜드의 셀러 귀속이 바뀌어도 "그때 누구 매출이었나"가 보존된다(Phase 2 셀러별 정산 Step 1b).
 *   브랜드 미지정 상품이거나 셀러 미귀속 브랜드면 null(미귀속 = 플랫폼 직매입 버킷).
 */
@Getter
@Entity
@Table(name = "order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Long productId;   // 다른 애그리거트(상품) → ID 참조

    @Column(nullable = false)
    private Long optionId;    // 주문한 옵션(사이즈) → ID 참조

    private Long brandId;     // 브랜드 참조(ID, nullable) — 주문 시점 스냅샷
    private Long sellerId;    // 셀러 참조(ID, nullable) — 주문 시점 스냅샷(셀러별 정산 귀속)

    @Column(nullable = false, length = 100)
    private String productName;   // 주문 시점 스냅샷

    @Column(nullable = false, length = 30)
    private String size;          // 주문 시점 사이즈 스냅샷

    @Column(nullable = false)
    private long orderPrice;      // 주문 시점 가격 스냅샷

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderItemStatus status;   // 부분환불(항목 단위 취소) 지원 — 기본 ACTIVE

    /** 취소 사유(#8, 기록·집계 전용). 항목 취소 시 세팅, 그 전엔 null. 시스템 취소(만료 등)는 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private com.commerce.api.global.common.CancelReason cancelReason;

    @Builder
    private OrderItem(Long productId, Long optionId, Long brandId, Long sellerId, String productName,
                      String size, long orderPrice, int quantity) {
        this.productId = productId;
        this.optionId = optionId;
        this.brandId = brandId;
        this.sellerId = sellerId;
        this.productName = productName;
        this.size = size;
        this.orderPrice = orderPrice;
        this.quantity = quantity;
        this.status = OrderItemStatus.ACTIVE;
    }

    /** 항목 취소(부분환불) — 사유 미상(시스템·내부). {@link #cancel(com.commerce.api.global.common.CancelReason)}로 위임. */
    public void cancel() {
        cancel(null);
    }

    /** 항목 취소(부분환불) + 사유 기록(#8). ACTIVE에서만 — 이미 취소·반품된 항목이면 409(이중 원장 차단). */
    public void cancel(com.commerce.api.global.common.CancelReason reason) {
        if (this.status != OrderItemStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "취소할 수 없는 주문 항목입니다. (현재: " + this.status + ")");
        }
        this.status = OrderItemStatus.CANCELLED;
        this.cancelReason = reason;
    }

    /** 항목 반품 확정(#3). ACTIVE에서만 RETURNED로 — 취소된 항목 반품·이중 반품을 구조적으로 차단. */
    public void markReturned() {
        if (this.status != OrderItemStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "반품할 수 없는 주문 항목입니다. (현재: " + this.status + ")");
        }
        this.status = OrderItemStatus.RETURNED;
    }

    public boolean isActive() {
        return this.status == OrderItemStatus.ACTIVE;
    }

    /**
     * 교환(#3 P6) — 대체 옵션으로 스왑. 원 항목을 ACTIVE로 유지하고 optionId/size만 바꾼다(가격·수량·셀러·id 불변).
     * getSubtotal 불변이라 discountShares·payable·정산이 무변경 정합(동일가 교환=돈/정산 델타 0, revenue-neutral).
     * 새 OrderItem을 만들면 discountShares basis가 이중계상되므로 스왑 방식을 쓴다. ACTIVE에서만.
     */
    public void swapOption(Long newOptionId, String newSize) {
        if (this.status != OrderItemStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "교환할 수 없는 주문 항목입니다. (현재: " + this.status + ")");
        }
        this.optionId = newOptionId;
        this.size = newSize;
    }

    /** 양방향 연관 설정 (Order.addItem에서 호출) */
    void assignOrder(Order order) {
        this.order = order;
    }

    /** 항목 소계 = 가격 × 수량 */
    public long getSubtotal() {
        return orderPrice * quantity;
    }
}