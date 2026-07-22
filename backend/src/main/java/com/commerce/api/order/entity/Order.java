package com.commerce.api.order.entity;

import com.commerce.api.global.common.BaseEntity;
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

    /** 택배사 (배송 시작 시 입력, 없으면 null). */
    @Column(length = 40)
    private String courier;

    /** 운송장 번호 (배송 시작 시 입력, 없으면 null). */
    @Column(name = "tracking_number", length = 60)
    private String trackingNumber;

    /**
     * 상태 이력 (애그리거트 내부 — 전이마다 append). append-only라 정렬은 id 오름차순(발생 순).
     * 전이 메서드가 스스로 기록하므로 "이력 없이 상태만 바뀌는" 일이 구조적으로 불가능하다.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("id asc")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    private Order(Long memberId) {
        this.memberId = memberId;
        this.status = OrderStatus.PENDING;   // 생성 시점 = 결제 대기
        this.totalPrice = 0L;
        this.discountAmount = 0L;            // 쿠폰 미적용 기본값
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

    /** 실제 결제 대상 금액 = 총액(gross) - 쿠폰 할인액. PG 승인·Payment.amount의 기준. */
    public long getPayableAmount() {
        return this.totalPrice - this.discountAmount;
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

    /** 주문 취소 (이미 취소된 주문·배송 시작된 주문은 불가). 주체/사유 미상(시스템·내부 호출). */
    public void cancel() {
        cancel(null, null);
    }

    /** 주문 취소 + 이력 기록 — changedBy(취소한 회원, 시스템이면 null)·memo(사유). */
    public void cancel(Long changedBy, String memo) {
        if (this.status == OrderStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 취소된 주문입니다.");
        }
        ensureCancellable();
        OrderStatus from = this.status;
        this.status = OrderStatus.CANCELLED;
        recordHistory(from, OrderStatus.CANCELLED, changedBy, memo);
    }

    /**
     * 항목 단위 취소(부분환불). 해당 항목을 CANCELLED로 만들고, 남은 활성 항목이 없으면 주문도 CANCELLED.
     * 취소된 항목을 반환한다(환불 금액·셀러 식별용). 없는 항목이면 404, 이미 취소면 409, 배송 시작됐으면 409.
     */
    public OrderItem cancelItem(Long orderItemId) {
        return cancelItem(orderItemId, null);
    }

    /** 항목 단위 취소 + (주문 전체가 취소되면) 이력 기록. changedBy = 취소한 회원(시스템이면 null). */
    public OrderItem cancelItem(Long orderItemId, Long changedBy) {
        ensureCancellable();
        OrderItem target = orderItems.stream()
                .filter(i -> orderItemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."));
        OrderStatus from = this.status;
        target.cancel();
        if (orderItems.stream().noneMatch(OrderItem::isActive)) {
            this.status = OrderStatus.CANCELLED;   // 모든 항목 취소 → 주문도 취소
            recordHistory(from, OrderStatus.CANCELLED, changedBy, "전체 항목 취소");
        }
        return target;
    }

    /** 결제 완료 처리 (PENDING → PAID). 결제 대기 상태가 아니면 예외. */
    public void markPaid() {
        if (this.status != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "결제 대기 상태의 주문만 결제할 수 있습니다.");
        }
        this.status = OrderStatus.PAID;
        recordHistory(OrderStatus.PENDING, OrderStatus.PAID, null, "결제 완료");
    }

    /** 배송 상태 전진(ADMIN) — 송장·주체 없이(기존 호출 호환). */
    public void advanceShipping(OrderStatus next) {
        advanceShipping(next, null, null, null);
    }

    /**
     * 배송 상태 전진(ADMIN) + 이력·송장 기록. 전이는 <b>forward-only</b>로 PAID → SHIPPING → DELIVERED 만 허용한다.
     * 건너뛰기(PAID→DELIVERED)·되돌리기(SHIPPING→PAID)·그 외 상태(PENDING/CANCELLED 출발·도착)는 409.
     *
     * <p>SHIPPING으로 갈 때 택배사·운송장을 함께 받아 저장한다(구매자에게 노출). DELIVERED 전이엔 무시.
     */
    public void advanceShipping(OrderStatus next, Long changedBy, String courier, String trackingNumber) {
        boolean allowed =
                (this.status == OrderStatus.PAID && next == OrderStatus.SHIPPING)
                || (this.status == OrderStatus.SHIPPING && next == OrderStatus.DELIVERED);
        if (!allowed) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "배송 상태를 " + this.status + "에서 " + next
                            + "(으)로 변경할 수 없습니다. (PAID→SHIPPING→DELIVERED 순서만 가능)");
        }
        OrderStatus from = this.status;
        this.status = next;

        String memo = null;
        if (next == OrderStatus.SHIPPING) {
            this.courier = courier;
            this.trackingNumber = trackingNumber;
            if (courier != null || trackingNumber != null) {
                memo = (courier == null ? "" : courier)
                        + (trackingNumber == null ? "" : " " + trackingNumber);
                memo = memo.trim();
            }
        }
        recordHistory(from, next, changedBy, memo);
    }

    /** 취소 가능 상태인지 — 배송이 시작(SHIPPING)되거나 완료(DELIVERED)된 주문은 취소할 수 없다(409). */
    private void ensureCancellable() {
        if (this.status == OrderStatus.SHIPPING || this.status == OrderStatus.DELIVERED) {
            throw new BusinessException(HttpStatus.CONFLICT, "배송이 시작된 주문은 취소할 수 없습니다.");
        }
    }

    /** 결제 완료(재고가 차감된) 주문인지 — 취소 시 재고 복원 여부 판단에 사용. */
    public boolean isPaid() {
        return this.status == OrderStatus.PAID;
    }
}