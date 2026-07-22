package com.commerce.api.order.entity;

import com.commerce.api.global.common.BaseEntity;
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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 상태 이력 1건 — Order 애그리거트 내부(OrderItem과 동형: 객체 연관).
 *
 * <p>전이가 일어날 때마다 {@link Order}가 append한다(엔티티가 이력 기록을 강제 → "전이하면 반드시 흔적이 남는다").
 * append-only 로그라 수정 메서드가 없다. {@code from_status}는 생성 시점(이전 상태 없음)엔 null.
 * {@code changed_by}는 변경 주체(회원 ID)이며 시스템/스케줄러 전이(만료 배치 등)는 null.
 */
@Getter
@Entity
@Table(name = "order_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;   // 소속 주문(같은 애그리거트 내부 역참조)

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private OrderStatus fromStatus;   // 이전 상태 (생성 시엔 null)

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private OrderStatus toStatus;

    @Column(name = "changed_by")
    private Long changedBy;   // 변경 주체 회원 ID (시스템/스케줄러면 null)

    @Column(length = 255)
    private String memo;      // 송장(택배사/운송장)·취소 사유 등 부가 설명

    private OrderStatusHistory(Order order, OrderStatus fromStatus, OrderStatus toStatus,
                               Long changedBy, String memo) {
        this.order = order;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.memo = memo;
    }

    /** 이력 1건 생성 — {@link Order}의 전이 메서드에서만 호출한다. */
    static OrderStatusHistory of(Order order, OrderStatus fromStatus, OrderStatus toStatus,
                                 Long changedBy, String memo) {
        return new OrderStatusHistory(order, fromStatus, toStatus, changedBy, memo);
    }
}
