package com.commerce.api.order.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.common.CancelReason;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주문 (애그리거트 루트).
 *
 * - 테이블명은 "orders" (ORDER는 SQL 예약어).
 * - 회원은 ID 참조(memberId, 다른 애그리거트). 주문 항목은 애그리거트 내부 → @OneToMany 객체 연관.
 * - totalPrice는 항목 추가 시 누적 계산 → 애그리거트가 스스로 정합성을 유지.
 */
@Getter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 낙관적 락 버전 — 주문 생명주기 전이(결제·취소·만료·배송)의 동시성 충돌을 감지한다.
     * 특히 <b>TTL 만료 배치와 결제가 같은 PENDING 주문을 동시에 처리</b>할 때, 늦게 커밋하는 쪽이 충돌로 실패해
     * "결제됐는데 만료 취소" 같은 상태 뒤집힘을 막는다(결제는 @Retryable로 재시도→이미 취소면 409).
     */
    @Version
    private Long version;

    @Column(nullable = false)
    private Long memberId;   // 다른 애그리거트(회원) → ID 참조

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private long totalPrice;   // 항목 소계 합(할인 전 gross). 항목별 원가 — 셀러별 정산 분해의 기준.

    /** 쿠폰 할인액(원). 쿠폰 미적용이면 0. 결제 대상 금액 = totalPrice - discountAmount. */
    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    /** 적용된 쿠폰 코드 스냅샷(없으면 null). 쿠폰 행이 바뀌어도 "무엇을 적용했는지" 보존. */
    @Column(name = "coupon_code", length = 40)
    private String couponCode;

    /**
     * 할인 부담 주체 스냅샷("PLATFORM"/"SELLER", 없으면 null). 셀러별 정산 분담(Step 2)이 읽는다.
     * 쿠폰 도메인 enum 대신 문자열 스냅샷으로 둬 order→coupon 결합을 피한다(productName 같은 원시 스냅샷 패턴).
     */
    @Column(name = "coupon_funded_by", length = 20)
    private String couponFundedBy;

    /** 셀러 한정 쿠폰이면 그 셀러 ID 스냅샷(플랫폼 와이드면 null). 정산이 할인을 그 셀러에 귀속할 때 쓴다. */
    @Column(name = "coupon_seller_id")
    private Long couponSellerId;

    /**
     * 배송비 스냅샷(#4). 체크아웃 시점에 "정액 + 무료임계" 정책으로 1회 계산해 고정한다(discountAmount와 동형 —
     * 이후 정책이 바뀌어도 이 주문의 결제액은 불변). payable에는 <b>활성 항목이 하나라도 있을 때만</b> 가산된다
     * ({@link #getPayableAmount()}) — 전량취소(활성 0)면 payable에서 빠져 배송비까지 환불되고, 부분취소는
     * 활성이 남아 배송비가 유지된다(오너 결정: 전체취소만 환불). 배송비는 <b>플랫폼 수익</b>이라 셀러 정산 net에는
     * 들어가지 않는다(정산은 항목 실효가만 읽는다). 셀러별 아닌 주문 단위 단일 값.
     */
    @Column(name = "shipping_fee", nullable = false)
    private long shippingFee;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    /** 배송지 스냅샷(체크아웃 시 주소록에서 복사). 명시적 주문 생성 경로에선 null일 수 있다. */
    @Embedded
    private ShippingInfo shippingInfo;

    /**
     * 체크아웃 멱등키(중복 주문 방지) — 클라이언트가 체크아웃 화면 진입 시 1회 발급해 재시도에도 같은 값을 보낸다.
     *
     * <p>{@code Payment.idempotencyKey}와 같은 패턴을 <b>한 계층 위(주문)</b>로 올린 것. 결제와 달리
     * <b>nullable</b>: 기존 주문 행에 백필할 값이 없고, 멱등키 없이 만드는 내부 경로(관리자·배치)를 막지 않는다.
     * (MySQL UNIQUE는 NULL 중복을 허용한다.)
     */
    @Column(name = "idempotency_key", unique = true, length = 80)
    private String idempotencyKey;

    // 택배사·운송장은 주문 단위가 아니라 셀러별 shipment에 있다(#1 c안 P6에서 orders 컬럼 DROP·V46).

    /**
     * 상태 이력 (애그리거트 내부 — 전이마다 append). append-only라 정렬은 id 오름차순(발생 순).
     * 전이 메서드가 스스로 기록하므로 "이력 없이 상태만 바뀌는" 일이 구조적으로 불가능하다.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("id asc")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    /**
     * 셀러별 배송 단위(#1 c안 — 애그리거트 내부). 결제 시점에 활성 항목을 sellerId로 팬아웃해 생성한다(P2).
     * PENDING 주문은 비어 있다. {@link #status}는 이 shipment들의 rollup으로 재계산되는 파생값(P3).
     * <p>P1(현재)은 읽기 매핑만 — 생성/전이/rollup은 후속 phase에서 연결한다.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("id asc")
    private List<Shipment> shipments = new ArrayList<>();

    private Order(Long memberId) {
        this.memberId = memberId;
        this.status = OrderStatus.PENDING;   // 생성 시점 = 결제 대기
        this.totalPrice = 0L;
        this.discountAmount = 0L;            // 쿠폰 미적용 기본값
        this.shippingFee = 0L;               // 배송비 미산정 기본값(체크아웃에서 스냅샷)
        // 타임라인 시작점: 이전 상태 없음(null) → PENDING, 주체=주문한 회원.
        recordHistory(null, OrderStatus.PENDING, memberId, "주문 생성");
    }

    /** 빈 주문 생성 (항목은 addItem으로 추가) */
    public static Order create(Long memberId) {
        return new Order(memberId);
    }

    /** 상태 이력 1건 append — 모든 전이 메서드가 상태를 바꾼 뒤 이걸 호출한다(불변식). */
    private void recordHistory(OrderStatus from, OrderStatus to, Long changedBy, String memo) {
        this.statusHistory.add(OrderStatusHistory.of(this, from, to, changedBy, memo));
    }

    /**
     * 멱등키 부여 — 체크아웃 경로에서 저장 <b>직전</b>에 찍는다. 이미 있으면 덮어쓰지 않는다
     * (한 주문의 멱등키는 불변이어야 재시도 판정이 흔들리지 않는다).
     */
    public void assignIdempotencyKey(String key) {
        if (this.idempotencyKey == null) {
            this.idempotencyKey = key;
        }
    }

    /** 주문 항목 추가 + 양방향 연관 설정 + 총액 누적 */
    public void addItem(OrderItem item) {
        orderItems.add(item);
        item.assignOrder(this);
        this.totalPrice += item.getSubtotal();
    }

    /** 배송지 스냅샷 지정 (체크아웃 시). */
    public void ship(ShippingInfo shippingInfo) {
        this.shippingInfo = shippingInfo;
    }

    /**
     * 쿠폰 적용 (체크아웃 시 1회). 할인액·코드·분담 메타를 스냅샷하고 결제 대상 금액(payable)을 낮춘다.
     *
     * <p><b>항목 소계(gross)는 그대로 둔다</b> — 셀러별 정산 분해(Step 2)가 원가 기준 gross를 읽어
     * 할인을 셀러/플랫폼으로 안분해야 하기 때문. 할인은 [0, totalPrice] 범위로 가드(음수 결제 방지).
     * fundedBy("PLATFORM"/"SELLER")·sellerId는 정산 분담을 위한 스냅샷이다(Step 2).
     */
    public void applyCoupon(String couponCode, long discountAmount, String fundedBy, Long sellerId) {
        if (discountAmount < 0 || discountAmount > this.totalPrice) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "할인 금액이 주문 금액을 초과할 수 없습니다.");
        }
        this.couponCode = couponCode;
        this.discountAmount = discountAmount;
        this.couponFundedBy = fundedBy;
        this.couponSellerId = sellerId;
    }

    /**
     * 배송비 스냅샷 지정(#4, 체크아웃 시 1회). 배송비 계산(정액·무료임계)은 서비스({@code ShippingPolicy})가 하고
     * 엔티티는 값만 저장한다(discountAmount가 coupon 도메인에서 넘어오는 것과 같은 경계). 음수 가드.
     */
    public void assignShippingFee(long shippingFee) {
        if (shippingFee < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "배송비는 음수일 수 없습니다.");
        }
        this.shippingFee = shippingFee;
    }

    /**
     * 실제 결제 대상 금액 = <b>활성 항목</b>의 실효가(소계 − 안분 할인) 합 <b>+ 배송비(활성 항목이 있을 때만)</b>.
     * PG 승인·Payment.amount의 기준.
     *
     * <p>취소된 항목은 뺀다 — PENDING 중 항목을 취소하고 결제하면 그 항목까지 청구되던 버그를 막고,
     * 정산이 쓰는 "활성 항목 실효가" 불변식({@link #discountShares})과 한 출처로 맞춘다(대사 금액 불일치 방지).
     * 취소가 없는 정상 주문에선 {@code totalPrice − discountAmount + shippingFee}와 동일하다(모든 항목이 활성이므로).
     *
     * <p><b>배송비 접기(#4)</b>: 배송비는 <b>전량취소(모든 항목 CANCELLED)</b>에서만 환불된다 — 반품(RETURNED)은
     * 배송을 이미 소비했으므로 유지(오너 규칙: 전체취소만 환불·부분취소·반품은 유지). 따라서 CANCELLED가 아닌
     * 항목(ACTIVE 또는 RETURNED)이 하나라도 있으면 배송비를 payable에 유지한다. 이 조건부가 취소 환불 공식
     * (PaymentService {@code refundNow = (amount − refundedAmount) − 남은 payable})을 <b>변경 없이</b> 오너 규칙에 맞춘다:
     * 전량취소 → payable 0 → 배송비까지 전액 환불 / 부분취소 → 배송비가 남은 payable에 남아 유지 / 전량반품 →
     * payable=배송비 → 반품 환불(실효가)에 배송비 미포함이라 유지(과거 '활성 0'만 보던 판정은 전량반품을 전량취소로
     * 오인해 배송비를 잘못 환불·정산 상계했다 — 적대적리뷰 HIGH 교정).
     */
    public long getPayableAmount() {
        Map<OrderItem, Long> shares = discountShares();
        long itemsPayable = orderItems.stream()
                .filter(OrderItem::isActive)
                .mapToLong(item -> item.getSubtotal() - shares.getOrDefault(item, 0L))
                .sum();
        boolean shippingRetained = orderItems.stream()
                .anyMatch(item -> item.getStatus() != OrderItemStatus.CANCELLED);
        return itemsPayable + (shippingRetained ? shippingFee : 0L);
    }

    /**
     * 쿠폰 할인을 <b>항목별로 안분</b>한다(매출 비례, 잔차는 매출 최대 항목에). 항목의 "실효가"(= 소계 − 안분 할인)는
     * 부분환불 환불액과 셀러별 정산(활성 항목 실효가 합)의 <b>단일 출처</b>다 — 둘이 같은 값을 써야
     * 어떤 취소 순서에도 "Σ실효가 = 결제액"이 유지된다(과다환불·대사 불일치 방지).
     *
     * <p>적용 범위: 플랫폼 와이드(couponSellerId=null)는 모든 항목, 셀러 한정은 그 셀러 항목만. 범위 밖은 0.
     * 기준 매출은 <b>주문 시점 전체 항목</b>(취소분 포함) — 항목이 "구매 시 받은 할인"은 다른 항목이 취소돼도 변치 않는다.
     */
    public Map<OrderItem, Long> discountShares() {
        Map<OrderItem, Long> shares = new LinkedHashMap<>();
        for (OrderItem item : orderItems) {
            shares.put(item, 0L);
        }
        if (discountAmount <= 0) {
            return shares;
        }

        // 적용 범위 내 항목 + 기준 매출(전체 항목 기준, 취소분 포함)
        List<OrderItem> inScope = new ArrayList<>();
        long basis = 0;
        for (OrderItem item : orderItems) {
            if (couponSellerId == null || couponSellerId.equals(item.getSellerId())) {
                inScope.add(item);
                basis += item.getSubtotal();
            }
        }
        if (basis <= 0) {
            return shares;   // 적용 대상 매출 없음(방어)
        }

        long allocated = 0;
        OrderItem maxItem = null;
        long maxSubtotal = -1;
        for (OrderItem item : inScope) {
            long share = Math.round((double) discountAmount * item.getSubtotal() / basis);
            shares.put(item, share);
            allocated += share;
            if (item.getSubtotal() > maxSubtotal) {
                maxSubtotal = item.getSubtotal();
                maxItem = item;
            }
        }
        if (maxItem != null && allocated != discountAmount) {
            shares.merge(maxItem, discountAmount - allocated, Long::sum);   // 반올림 잔차 보정(Σ = discountAmount)
        }
        return shares;
    }

    /** 주문 취소 — 주체/사유 미상(시스템·내부 호출). 취소한 항목 목록 반환. */
    public List<OrderItem> cancel() {
        return cancel(null, null);
    }

    /**
     * 주문 취소(#1 c안) — 아직 <b>출고 전</b>(shipment PAID 또는 미결제)인 활성 항목을 모두 취소한다.
     * 이미 출고된 셀러 항목은 남고(부분 취소), 취소 가능한 항목이 하나도 없으면 409. 취소한 항목 목록을 반환한다
     * (재고 되돌리기·환불 금액 산정용 — 호출자 OrderService/PaymentService가 이 집합에만 재고 복원/환불을 적용).
     */
    public List<OrderItem> cancel(Long changedBy, String memo) {
        return cancel(changedBy, memo, null);
    }

    /** 주문 취소 + 취소 사유 기록(#8). 취소된 각 항목에 사유를 남긴다(기록·집계 전용). */
    public List<OrderItem> cancel(Long changedBy, String memo, CancelReason reason) {
        if (this.status == OrderStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 취소된 주문입니다.");
        }
        List<OrderItem> activeItems = orderItems.stream().filter(OrderItem::isActive).toList();
        List<OrderItem> cancellable = activeItems.stream().filter(this::isItemCancellable).toList();
        // 활성 항목이 있는데 하나도 취소할 수 없다(전부 출고) → 409. (항목 자체가 없는 주문은 그대로 취소.)
        if (!activeItems.isEmpty() && cancellable.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "배송이 시작되어 취소할 수 있는 항목이 없습니다.");
        }
        for (OrderItem item : cancellable) {
            cancelItemInternal(item, changedBy, reason);
        }
        applyCancellationRollup(changedBy, memo);
        return cancellable;
    }

    /** 항목 단위 취소 — 주체 미상. */
    public OrderItem cancelItem(Long orderItemId) {
        return cancelItem(orderItemId, null, null);
    }

    /**
     * 항목 단위 취소(부분환불, #1 c안) — 그 항목의 셀러 shipment가 <b>미출고(PAID)</b>이거나 shipment 없음(PENDING)일 때만.
     * 출고 시작(SHIPPING/DELIVERED)됐으면 409. 셀러의 마지막 활성 항목이면 그 shipment도 CANCELLED가 되고,
     * 모든 shipment(또는 PENDING의 모든 항목)가 취소되면 주문도 CANCELLED로 rollup된다. 취소된 항목을 반환한다.
     */
    public OrderItem cancelItem(Long orderItemId, Long changedBy) {
        return cancelItem(orderItemId, changedBy, null);
    }

    /** 항목 단위 취소 + 취소 사유 기록(#8). */
    public OrderItem cancelItem(Long orderItemId, Long changedBy, CancelReason reason) {
        OrderItem target = orderItems.stream()
                .filter(i -> orderItemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."));
        if (target.getStatus() == OrderItemStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 취소된 주문 항목입니다.");
        }
        if (!isItemCancellable(target)) {
            throw new BusinessException(HttpStatus.CONFLICT, "배송이 시작된 항목은 취소할 수 없습니다.");
        }
        cancelItemInternal(target, changedBy, reason);
        applyCancellationRollup(changedBy, "항목 취소");
        return target;
    }

    /** 항목을 CANCELLED(사유 기록)로 만들고, 그 셀러의 남은 활성 항목이 없으면 그 셀러 shipment도 취소한다(내부 공통). */
    private void cancelItemInternal(OrderItem item, Long changedBy, CancelReason reason) {
        item.cancel(reason);
        Shipment shipment = shipmentForItem(item);
        if (shipment != null && shipment.getStatus() == ShipmentStatus.PAID) {
            boolean sellerHasActive = orderItems.stream()
                    .filter(OrderItem::isActive)
                    .anyMatch(i -> java.util.Objects.equals(i.getSellerId(), item.getSellerId()));
            if (!sellerHasActive) {
                shipment.cancel(changedBy, "셀러 항목 전량 취소");
            }
        }
    }

    /** 취소 후 주문 상태 재계산 — shipment가 있으면 rollup, 없으면(PENDING) 활성 항목이 0이 되면 CANCELLED. */
    private void applyCancellationRollup(Long changedBy, String memo) {
        if (!shipments.isEmpty()) {
            recomputeStatusFromShipments(changedBy, memo);
            return;
        }
        if (this.status != OrderStatus.CANCELLED && orderItems.stream().noneMatch(OrderItem::isActive)) {
            OrderStatus from = this.status;
            this.status = OrderStatus.CANCELLED;
            recordHistory(from, OrderStatus.CANCELLED, changedBy, memo);
        }
    }

    /** 그 항목이 취소 가능한가 — shipment 없음(PENDING)이거나, 그 셀러 shipment가 아직 미출고(PAID)면 가능. */
    private boolean isItemCancellable(OrderItem item) {
        Shipment shipment = shipmentForItem(item);
        return shipment == null || shipment.getStatus() == ShipmentStatus.PAID;
    }

    /** 이 항목이 속한 셀러의 <b>원배송</b> shipment(없으면 null — PENDING). 교환 재출고(EXCHANGE)는 제외(#3). null-safe 매칭. */
    private Shipment shipmentForItem(OrderItem item) {
        return shipments.stream()
                .filter(Shipment::isOriginal)
                .filter(s -> s.belongsToSeller(item.getSellerId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 반품/교환 요청 자격 검증(#3 P3) — 통과하면 요청 생성에 필요한 스냅샷(shipmentId·sellerId·quantity)을 돌려준다.
     * 게이트: 항목이 <b>ACTIVE</b>(이미 취소·반품된 항목 차단 — 과다환불 방지), 그 셀러 원배송이 <b>DELIVERED</b>,
     * 배송완료 후 {@code windowDays}일 이내. deliveredAt이 null(레거시 이력 부재)이면 기한 판정 유예(허용).
     */
    public ReturnableItem ensureReturnable(Long orderItemId, int windowDays) {
        OrderItem item = orderItems.stream()
                .filter(i -> orderItemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."));
        if (!item.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "반품할 수 없는 항목입니다. (현재: " + item.getStatus() + ")");
        }
        Shipment shipment = shipmentForItem(item);
        if (shipment == null || shipment.getStatus() != ShipmentStatus.DELIVERED) {
            throw new BusinessException(HttpStatus.CONFLICT, "배송 완료된 항목만 반품/교환할 수 있습니다.");
        }
        java.time.LocalDateTime deliveredAt = shipment.getDeliveredAt();
        if (deliveredAt != null && deliveredAt.plusDays(windowDays).isBefore(java.time.LocalDateTime.now())) {
            throw new BusinessException(HttpStatus.CONFLICT, "반품 기한(" + windowDays + "일)이 지났습니다.");
        }
        return new ReturnableItem(shipment.getId(), item.getSellerId(), item.getQuantity());
    }

    /** 반품 요청 생성에 필요한 주문 측 스냅샷(#3). sellerId는 여기서 서버가 도출(클라 입력 금지 — IDOR 방지). */
    public record ReturnableItem(Long shipmentId, Long sellerId, int quantity) {
    }

    /**
     * 교환 재출고 shipment를 추가한다(#3 P6, kind=EXCHANGE). rollup·항목 배송 판정에서 제외되므로 DELIVERED 주문이
     * 후퇴하지 않는다. 반환한 shipment의 id는 cascade flush 후 채워진다(호출자가 flush 후 사용).
     */
    public Shipment addExchangeShipment(Long sellerId) {
        Shipment exchange = Shipment.forExchange(this, sellerId);
        this.shipments.add(exchange);
        return exchange;
    }

    /** 주문 항목 조회 — 없으면 404(#3 반품 확정 등). */
    public OrderItem requireItem(Long orderItemId) {
        return orderItems.stream()
                .filter(i -> orderItemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."));
    }

    /**
     * 항목 실효가 = 소계 − 안분 할인({@link #discountShares} 단일 출처). 환불액의 단일 출처 — 취소·반품이 같은 값을 써야
     * "Σ실효가=결제액" 불변식이 유지된다(과다환불·대사 불일치 방지).
     */
    public long effectivePriceOf(OrderItem item) {
        return item.getSubtotal() - discountShares().getOrDefault(item, 0L);
    }

    /** 결제 완료 처리 (PENDING → PAID). 결제 대기 상태가 아니면 예외. 결제 시점에 셀러별 shipment를 팬아웃 생성한다(#1 P2). */
    public void markPaid() {
        if (this.status != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "결제 대기 상태의 주문만 결제할 수 있습니다.");
        }
        this.status = OrderStatus.PAID;
        recordHistory(OrderStatus.PENDING, OrderStatus.PAID, null, "결제 완료");
        createShipmentsForPayment();
    }

    /** 활성 항목의 셀러별 distinct 집합(insertion 순서 유지, null=플랫폼 버킷 포함) — shipment 팬아웃의 그룹 키. */
    private java.util.Set<Long> distinctActiveSellerIds() {
        java.util.Set<Long> sellers = new java.util.LinkedHashSet<>();   // null 키 1개 허용(플랫폼 버킷)
        for (OrderItem item : orderItems) {
            if (item.isActive()) {
                sellers.add(item.getSellerId());
            }
        }
        return sellers;
    }

    /**
     * 결제 시점 팬아웃(#1 P2) — 활성 항목을 셀러별로 묶어 shipment 1건씩(status=PAID) 생성한다.
     * 전량 취소된 셀러는 활성 항목이 없어 shipment가 안 생긴다(정산 활성-항목 기준과 정합).
     */
    private void createShipmentsForPayment() {
        for (Long sellerId : distinctActiveSellerIds()) {
            this.shipments.add(Shipment.forPayment(this, sellerId));
        }
    }

    /**
     * 백필(#1 P2) — shipment 없는 기존 PURCHASED 주문에 <b>현재 주문 상태를 상속</b>한 shipment를 소급 생성한다.
     * per-order 멱등(이미 shipment가 있으면 no-op)이라 재실행에 안전. PENDING(shipment 없음)·CANCELLED(생략)는 건너뛴다.
     * SHIPPING/DELIVERED면 주문 단위 송장(courier/tracking)을 각 shipment에 복제한다(상태 동일 상속이라 무해).
     *
     * @return 이번 호출로 shipment를 생성했으면 true(백필 대상이었음)
     */
    public boolean backfillShipments() {
        if (!shipments.isEmpty()) {
            return false;   // per-order 멱등
        }
        ShipmentStatus target = switch (this.status) {
            case PAID -> ShipmentStatus.PAID;
            case SHIPPING -> ShipmentStatus.SHIPPING;
            case DELIVERED -> ShipmentStatus.DELIVERED;
            case PENDING, CANCELLED -> null;   // PENDING=shipment 없음, CANCELLED=출고 무의미라 생략
        };
        if (target == null) {
            return false;
        }
        for (Long sellerId : distinctActiveSellerIds()) {
            this.shipments.add(Shipment.forBackfill(this, sellerId, target));   // 레거시 주문은 셀러별 송장 정보가 없다
        }
        return !shipments.isEmpty();
    }

    /** 배송 상태 전진(ADMIN 일괄) — 송장·주체 없이(기존 호출 호환). */
    public void advanceShipping(OrderStatus next) {
        advanceShipping(next, null, null, null);
    }

    /**
     * 배송 상태 전진(ADMIN 편의, #1 c안) — 주문의 <b>활성 shipment를 일괄</b> 다음 단계로 전진하고
     * {@link #status}를 shipment rollup으로 재계산한다. 셀러별 개별 전진은 shipment 단위 경로(P5)가 담당한다.
     *
     * <p>전이는 forward-only PAID→SHIPPING→DELIVERED. next는 SHIPPING/DELIVERED만 유효하고, 전진 가능한
     * shipment가 하나도 없으면 409(건너뛰기·되돌리기·PENDING/CANCELLED에서의 전진 차단 — 기존 order-grain 의미 보존).
     * SHIPPING 전이 시 송장은 각 shipment에 저장하고, order-level courier/tracking에도 복제한다(P6에서 컬럼 제거 전까지 응답 호환).
     */
    public void advanceShipping(OrderStatus next, Long changedBy, String courier, String trackingNumber) {
        ShipmentStatus prereq;
        ShipmentStatus target;
        if (next == OrderStatus.SHIPPING) {
            prereq = ShipmentStatus.PAID;
            target = ShipmentStatus.SHIPPING;
        } else if (next == OrderStatus.DELIVERED) {
            prereq = ShipmentStatus.SHIPPING;
            target = ShipmentStatus.DELIVERED;
        } else {
            throw shippingConflict(next);   // PENDING/PAID/CANCELLED로의 전진은 무효
        }

        int advanced = 0;
        for (Shipment s : shipments) {
            // 교환 재출고(EXCHANGE)는 ADMIN 일괄 전진에서 제외 — 재출고건은 shipmentId 직접 경로로만 전이한다(#3).
            if (s.isOriginal() && s.isActive() && s.getStatus() == prereq) {
                s.advanceShipping(target, changedBy, courier, trackingNumber);
                advanced++;
            }
        }
        if (advanced == 0) {
            throw shippingConflict(next);   // 전진 가능한 shipment 없음(건너뛰기·되돌리기·미결제 등)
        }
        // 송장은 각 shipment에 저장됐다(order-level courier/tracking 컬럼은 P6에서 제거).
        recomputeStatusFromShipments(changedBy, shippingMemo(next, courier, trackingNumber));
    }

    private BusinessException shippingConflict(OrderStatus next) {
        return new BusinessException(HttpStatus.CONFLICT,
                "배송 상태를 " + this.status + "에서 " + next
                        + "(으)로 변경할 수 없습니다. (PAID→SHIPPING→DELIVERED 순서만 가능)");
    }

    private static String shippingMemo(OrderStatus next, String courier, String trackingNumber) {
        if (next == OrderStatus.SHIPPING && (courier != null || trackingNumber != null)) {
            return ((courier == null ? "" : courier)
                    + (trackingNumber == null ? "" : " " + trackingNumber)).trim();
        }
        return null;
    }

    /**
     * shipment들의 상태를 {@link #status}에 반영한다(rollup 파생, #1 c안). 값이 <b>실제로 바뀔 때만</b> 이력 1건 append
     * ("전이=흔적" 불변식 유지, 셀러 A만 전진해 rollup이 불변이면 shipment 이력만 남는다).
     *
     * <p>rollup 규칙(활성=비취소 shipment 기준, forward-only라 값 후퇴 불가):
     * 전부 취소→CANCELLED / 전부 DELIVERED→DELIVERED / 하나라도 출고 시작(SHIPPING·DELIVERED)→SHIPPING / 전부 PAID→PAID.
     * 저장된 파생 컬럼이라 PURCHASED 리더(리뷰자격·추천·대시보드)와 기존 인덱스가 무변경 생존한다.
     */
    public void recomputeStatusFromShipments(Long changedBy, String memo) {
        if (shipments.isEmpty()) {
            return;   // 결제 전(PENDING) — shipment 없음
        }
        OrderStatus rolled = rollupStatus();
        if (rolled != this.status) {
            OrderStatus from = this.status;
            this.status = rolled;
            recordHistory(from, rolled, changedBy, memo);
        }
    }

    private OrderStatus rollupStatus() {
        // 교환 재출고(EXCHANGE)는 주문 상태 rollup에서 제외 — 안 그러면 DELIVERED 주문이 재출고로 SHIPPING 후퇴(#3).
        List<Shipment> active = shipments.stream()
                .filter(Shipment::isOriginal).filter(Shipment::isActive).toList();
        if (active.isEmpty()) {
            return OrderStatus.CANCELLED;   // 모든 원배송 shipment 취소 → 주문 취소
        }
        if (active.stream().allMatch(s -> s.getStatus() == ShipmentStatus.DELIVERED)) {
            return OrderStatus.DELIVERED;
        }
        boolean anyStarted = active.stream()
                .anyMatch(s -> s.getStatus() == ShipmentStatus.SHIPPING || s.getStatus() == ShipmentStatus.DELIVERED);
        return anyStarted ? OrderStatus.SHIPPING : OrderStatus.PAID;
    }
}