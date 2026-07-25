package com.commerce.api.order.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 배송 단위(shipment) — {@link Order} 애그리거트 내부, <b>셀러별</b> 출고 묶음(#1 c안).
 *
 * <p>한 주문에 여러 셀러 상품이 섞이면 셀러마다 shipment 1건을 갖는다(결제 시점에 활성 항목을 sellerId로 팬아웃 —
 * P2). {@code sellerId=null}은 플랫폼 직매입 버킷(ADMIN이 출고). 항목과의 연결은 FK가 아니라
 * <b>(order, sellerId) 매칭</b>으로 암묵적이다({@link OrderItem#getSellerId()}와 null-safe 대조) — order_item 스키마를 건드리지 않는다.
 *
 * <p>각 shipment는 자기 {@link #version 낙관적 락}으로 독립 전진하므로, 셀러 A·B가 동시에 출고해도 서로 막지 않는다.
 * 전이는 forward-only(PAID → SHIPPING → DELIVERED). {@link Order#getStatus()}는 shipment들의 rollup 파생값이다(P3).
 *
 * <p><b>P1(현재)</b>: 스키마·전이 도메인 로직만. 결제 팬아웃 생성(P2)·주문 rollup 재계산(P3)·셀러 인가(P5)는 아직 미연결.
 */
@Getter
@Entity
@Table(name = "shipment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * shipment별 낙관적 락 — 같은 shipment 동시 전진을 충돌 감지(늦은 커밋 실패 → @Retryable 재시도).
     * Order.version과 <b>별개</b>라, 셀러 A·B가 각자 shipment를 전진할 때 공유 orders 행에서 서로 충돌하지 않는다.
     */
    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;   // 소속 주문(같은 애그리거트 내부 역참조)

    /** 셀러 참조(ID, nullable) — 주문 항목 스냅샷과 동일 축. null = 플랫폼 직매입 버킷(ADMIN 출고). */
    @Column(name = "seller_id")
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status;

    /** 택배사 (SHIPPING 전이 시 입력, 없으면 null) — 셀러별 개별 송장. */
    @Column(length = 40)
    private String courier;

    /** 운송장 번호 (SHIPPING 전이 시 입력, 없으면 null). */
    @Column(name = "tracking_number", length = 60)
    private String trackingNumber;

    /** 배송 완료 시각 (DELIVERED 전이 시 세팅) — 반품 기한(DELIVERED+N일) O(1) 판정용 비정규화(이력 스캔 회피, #3). */
    @Column(name = "delivered_at")
    private java.time.LocalDateTime deliveredAt;

    /** 원배송(ORIGINAL) vs 교환 재출고(EXCHANGE, #3). EXCHANGE는 주문 rollup·항목 배송 판정에서 제외된다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentKind kind;

    /**
     * 상태 이력 (애그리거트 내부 — 전이마다 append). append-only라 정렬은 id 오름차순(발생 순).
     * 전이 메서드가 스스로 기록하므로 "이력 없이 상태만 바뀌는" 일이 구조적으로 불가능하다({@link Order#statusHistory}와 동형).
     */
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("id asc")
    private List<ShipmentStatusHistory> statusHistory = new ArrayList<>();

    private Shipment(Order order, Long sellerId, ShipmentStatus initialStatus, ShipmentKind kind, String memo) {
        this.order = order;
        this.sellerId = sellerId;
        this.status = initialStatus;
        this.kind = kind;
        recordHistory(null, initialStatus, null, memo);
    }

    /**
     * 결제 시점 팬아웃으로 shipment 1건 생성(셀러당 1건, sellerId=null=플랫폼 버킷). 초기 상태 PAID.
     * {@link Order#markPaid()}가 셀러별로 호출한다(P2).
     */
    public static Shipment forPayment(Order order, Long sellerId) {
        return new Shipment(order, sellerId, ShipmentStatus.PAID, ShipmentKind.ORIGINAL, "결제 완료 · 출고 대기");
    }

    /**
     * 백필(P2) — shipment 없는 기존 PURCHASED 주문에 <b>현재 주문 상태를 상속</b>한 shipment를 소급 생성한다
     * ({@link Order#backfillShipments()}가 호출). 이력은 단일 항목(null→status, "백필"). 레거시 주문은 셀러별
     * 개별 송장 정보가 없으므로 courier/tracking은 비운다(orders의 단일 송장 컬럼은 P6에서 DROP).
     */
    public static Shipment forBackfill(Order order, Long sellerId, ShipmentStatus status) {
        return new Shipment(order, sellerId, status, ShipmentKind.ORIGINAL, "백필(기존 주문 상태 소급)");
    }

    /** 상태 이력 1건 append — 모든 전이 메서드가 상태를 바꾼 뒤 이걸 호출한다(불변식). */
    private void recordHistory(ShipmentStatus from, ShipmentStatus to, Long changedBy, String memo) {
        this.statusHistory.add(ShipmentStatusHistory.of(this, from, to, changedBy, memo));
    }

    /**
     * 배송 상태 전진 + 이력·송장 기록. 전이는 <b>forward-only</b>로 PAID → SHIPPING → DELIVERED 만 허용한다
     * (건너뛰기·되돌리기·CANCELLED 출발/도착은 409). {@link Order#advanceShipping}에서 이관한 규칙.
     *
     * <p>SHIPPING으로 갈 때 택배사·운송장을 함께 받아 저장한다(구매자에게 노출). DELIVERED 전이엔 무시.
     */
    public void advanceShipping(ShipmentStatus next, Long changedBy, String courier, String trackingNumber) {
        boolean allowed =
                (this.status == ShipmentStatus.PAID && next == ShipmentStatus.SHIPPING)
                || (this.status == ShipmentStatus.SHIPPING && next == ShipmentStatus.DELIVERED);
        if (!allowed) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송 상태를 " + this.status + "에서 " + next
                            + "(으)로 변경할 수 없습니다. (PAID→SHIPPING→DELIVERED 순서만 가능)");
        }
        ShipmentStatus from = this.status;
        this.status = next;

        String memo = null;
        if (next == ShipmentStatus.SHIPPING) {
            this.courier = courier;
            this.trackingNumber = trackingNumber;
            if (courier != null || trackingNumber != null) {
                memo = ((courier == null ? "" : courier)
                        + (trackingNumber == null ? "" : " " + trackingNumber)).trim();
            }
        } else if (next == ShipmentStatus.DELIVERED) {
            this.deliveredAt = java.time.LocalDateTime.now();   // 반품 기한 기산점(#3)
        }
        recordHistory(from, next, changedBy, memo);
    }

    /**
     * shipment 취소 — 이 셀러 항목이 전부 취소될 때(P4). 출고 전(PAID)에서만 가능하고, 이미 CANCELLED면 no-op.
     * SHIPPING/DELIVERED는 취소 불가(취소 가능 판정은 {@link Order}의 shipment-grain ensureCancellable가 선차단).
     */
    public void cancel(Long changedBy, String memo) {
        if (this.status == ShipmentStatus.CANCELLED) {
            return;   // 멱등
        }
        if (this.status != ShipmentStatus.PAID) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 배송이 시작된 배송 건은 취소할 수 없습니다.");
        }
        ShipmentStatus from = this.status;
        this.status = ShipmentStatus.CANCELLED;
        recordHistory(from, ShipmentStatus.CANCELLED, changedBy, memo);
    }

    /** 취소되지 않은 shipment인지 — 주문 상태 rollup에서 "비취소 shipment 기준" 판정에 쓴다(P3). */
    public boolean isActive() {
        return this.status != ShipmentStatus.CANCELLED;
    }

    /** 원배송(교환 재출고가 아님)인지 — rollup·항목 배송 판정·일괄 전진이 원배송만 보게 한다(#3). */
    public boolean isOriginal() {
        return this.kind == ShipmentKind.ORIGINAL;
    }

    /** 이 shipment가 주어진 셀러 것인지 — null(플랫폼 버킷) null-safe 매칭. */
    public boolean belongsToSeller(Long sellerId) {
        return java.util.Objects.equals(this.sellerId, sellerId);
    }
}
