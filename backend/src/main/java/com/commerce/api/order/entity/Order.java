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
import java.util.List;
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
    private long totalPrice;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    /** 배송지 스냅샷(체크아웃 시 주소록에서 복사). 명시적 주문 생성 경로에선 null일 수 있다. */
    @Embedded
    private ShippingInfo shippingInfo;

    private Order(Long memberId) {
        this.memberId = memberId;
        this.status = OrderStatus.PENDING;   // 생성 시점 = 결제 대기
        this.totalPrice = 0L;
    }

    /** 빈 주문 생성 (항목은 addItem으로 추가) */
    public static Order create(Long memberId) {
        return new Order(memberId);
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

    /** 주문 취소 (이미 취소된 주문은 불가) */
    public void cancel() {
        if (this.status == OrderStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 취소된 주문입니다.");
        }
        this.status = OrderStatus.CANCELLED;
    }

    /**
     * 항목 단위 취소(부분환불). 해당 항목을 CANCELLED로 만들고, 남은 활성 항목이 없으면 주문도 CANCELLED.
     * 취소된 항목을 반환한다(환불 금액·셀러 식별용). 없는 항목이면 404, 이미 취소면 409.
     */
    public OrderItem cancelItem(Long orderItemId) {
        OrderItem target = orderItems.stream()
                .filter(i -> orderItemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문 항목을 찾을 수 없습니다."));
        target.cancel();
        if (orderItems.stream().noneMatch(OrderItem::isActive)) {
            this.status = OrderStatus.CANCELLED;   // 모든 항목 취소 → 주문도 취소
        }
        return target;
    }

    /** 결제 완료 처리 (PENDING → PAID). 결제 대기 상태가 아니면 예외. */
    public void markPaid() {
        if (this.status != OrderStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "결제 대기 상태의 주문만 결제할 수 있습니다.");
        }
        this.status = OrderStatus.PAID;
    }

    /** 결제 완료(재고가 차감된) 주문인지 — 취소 시 재고 복원 여부 판단에 사용. */
    public boolean isPaid() {
        return this.status == OrderStatus.PAID;
    }
}