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
 * 배송 상태 이력 1건 — {@link Shipment} 애그리거트 내부({@link OrderStatusHistory}와 동형).
 *
 * <p>shipment 전이가 일어날 때마다 {@link Shipment}가 append한다("전이하면 반드시 흔적이 남는다"). append-only라
 * 수정 메서드가 없다. {@code from_status}는 생성 시점(이전 상태 없음)엔 null. {@code changed_by}는 변경 주체
 * (셀러 또는 ADMIN 회원 ID)이며 시스템 전이(결제 시 생성 등)는 null. 셀러별 감사 타임라인을 위해
 * order_status_history에 얹지 않고 별도 테이블로 둔다(주문 전이 이력의 "반드시 흔적" 불변식을 흐리지 않음).
 */
@Getter
@Entity
@Table(name = "shipment_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentStatusHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;   // 소속 shipment(같은 애그리거트 내부 역참조)

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ShipmentStatus fromStatus;   // 이전 상태 (생성 시엔 null)

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ShipmentStatus toStatus;

    @Column(name = "changed_by")
    private Long changedBy;   // 변경 주체 회원 ID (셀러/ADMIN, 시스템이면 null)

    @Column(length = 255)
    private String memo;      // 송장(택배사/운송장)·취소 사유 등 부가 설명

    private ShipmentStatusHistory(Shipment shipment, ShipmentStatus fromStatus, ShipmentStatus toStatus,
                                  Long changedBy, String memo) {
        this.shipment = shipment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.memo = memo;
    }

    /** 이력 1건 생성 — {@link Shipment}의 전이 메서드에서만 호출한다. */
    static ShipmentStatusHistory of(Shipment shipment, ShipmentStatus fromStatus, ShipmentStatus toStatus,
                                    Long changedBy, String memo) {
        return new ShipmentStatusHistory(shipment, fromStatus, toStatus, changedBy, memo);
    }
}
