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

    /** 항목 취소(부분환불). 이미 취소면 409. */
    public void cancel() {
        if (this.status == OrderItemStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 취소된 주문 항목입니다.");
        }
        this.status = OrderItemStatus.CANCELLED;
    }

    public boolean isActive() {
        return this.status == OrderItemStatus.ACTIVE;
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