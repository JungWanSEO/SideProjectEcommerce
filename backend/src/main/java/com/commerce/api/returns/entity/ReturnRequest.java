package com.commerce.api.returns.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 반품/교환 요청 (독립 애그리거트 루트, #3).
 *
 * <p>배송완료(DELIVERED) 이후 시작되는 역방향·다단계 흐름을 담는다. 다른 애그리거트(주문/항목/배송/셀러/회원)는
 * <b>ID 참조</b>만 한다(Payment·SettlementEntry 동형 — DDD). 상태 전이는 이 엔티티가 강제한다(잘못된 전이·타입
 * 불일치 409). "이 항목이 유효한가"의 단일 출처는 어디까지나 {@code OrderItem.status}이고, 이 엔티티의 status는
 * <b>워크플로 진행용</b>이다(금액 판정 출처 아님).
 *
 * <p>동시성: 실제 상태/원장 변경은 호출자(ReturnService, P3+)가 <b>부모 주문 비관락</b>(findByIdForUpdate) 안에서
 * 직렬화한다 — 이 엔티티의 @Version은 보조 방어.
 */
@Getter
@Entity
@Table(name = "return_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long shipmentId;   // 원배송(반품 기한·셀러 매칭 축)

    @Column(name = "seller_id")
    private Long sellerId;     // 스냅샷(인가·정산 귀속). null=플랫폼 버킷 → ADMIN만 처리

    @Column(nullable = false)
    private Long memberId;     // 요청한 구매자

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnStatus status;

    @Column(length = 255)
    private String reason;   // 구매자 자유텍스트 사유(상세)

    /** 구조화된 사유 코드(#8, 기록·집계 전용). 반품/교환 요청 시 구매자가 선택. 없으면 null(레거시). */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 30)
    private com.commerce.api.global.common.CancelReason reasonCode;

    @Column(nullable = false)
    private int quantity;      // v1=라인 전량(OrderItem.quantity 스냅샷). 부분수량 반품은 후속(컬럼 선확보).

    /** 확정 환불액(검수확정 시 실효가로 확정, RETURN). 그 전엔 null. */
    private Long refundAmount;

    /** 재입고 여부(검수확정 시 결정 — 하자품은 write-off로 false). */
    @Column(nullable = false)
    private boolean restock;

    /** 교환 대상 옵션(EXCHANGE, 같은 상품 다른 옵션). RETURN이면 null. */
    private Long exchangeOptionId;

    /** 교환 재출고 shipment ID(EXCHANGE 확정 시 연결, P6). */
    private Long exchangeShipmentId;

    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("id asc")
    private List<ReturnStatusHistory> statusHistory = new ArrayList<>();

    private ReturnRequest(Long orderId, Long orderItemId, Long shipmentId, Long sellerId, Long memberId,
                          ReturnType type, String reason,
                          com.commerce.api.global.common.CancelReason reasonCode, int quantity, Long exchangeOptionId) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.shipmentId = shipmentId;
        this.sellerId = sellerId;
        this.memberId = memberId;
        this.type = type;
        this.reason = reason;
        this.reasonCode = reasonCode;
        this.quantity = quantity;
        this.exchangeOptionId = exchangeOptionId;
        this.restock = true;   // 기본 재입고, 검수에서 하자 시 false로
        this.status = ReturnStatus.REQUESTED;
        recordHistory(null, ReturnStatus.REQUESTED, memberId, reason);
    }

    /**
     * 반품/교환 요청 생성(REQUESTED). sellerId는 <b>대상 OrderItem에서 서버가 도출</b>해 넘긴다(클라 입력 금지 — IDOR).
     * 교환이면 exchangeOptionId 필수(같은 상품 다른 옵션), 반품이면 null. reasonCode(#8)는 구조화된 사유(선택).
     */
    public static ReturnRequest create(Long orderId, Long orderItemId, Long shipmentId, Long sellerId, Long memberId,
                                       ReturnType type, String reason,
                                       com.commerce.api.global.common.CancelReason reasonCode,
                                       int quantity, Long exchangeOptionId) {
        if (type == ReturnType.EXCHANGE && exchangeOptionId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "교환은 대상 옵션이 필요합니다.");
        }
        if (type == ReturnType.RETURN && exchangeOptionId != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "반품에는 교환 옵션을 지정할 수 없습니다.");
        }
        return new ReturnRequest(orderId, orderItemId, shipmentId, sellerId, memberId, type, reason,
                reasonCode, quantity, exchangeOptionId);
    }

    // === 상태 전이 (forward-only, 엔티티 강제) =========================================

    /** 승인(셀러/ADMIN): REQUESTED → APPROVED. */
    public void approve(Long changedBy) {
        transition(ReturnStatus.REQUESTED, ReturnStatus.APPROVED, changedBy, null);
    }

    /** 거부(셀러/ADMIN): REQUESTED(요청 거부) 또는 INSPECTED(검수 불합격) → REJECTED. 금액·재고 무영향. */
    public void reject(Long changedBy, String memo) {
        if (status != ReturnStatus.REQUESTED && status != ReturnStatus.INSPECTED) {
            throw invalid(ReturnStatus.REJECTED);
        }
        setStatus(ReturnStatus.REJECTED, changedBy, memo);
    }

    /** 수거 확인(셀러/ADMIN): APPROVED → PICKED_UP. */
    public void pickUp(Long changedBy) {
        transition(ReturnStatus.APPROVED, ReturnStatus.PICKED_UP, changedBy, null);
    }

    /** 검수 통과(셀러/ADMIN): PICKED_UP → INSPECTED. 이후 RETURN=환불, EXCHANGE=재출고. */
    public void inspect(Long changedBy) {
        transition(ReturnStatus.PICKED_UP, ReturnStatus.INSPECTED, changedBy, null);
    }

    /** 반품 확정: INSPECTED → REFUNDED (RETURN 전용). 환불액·재입고여부 확정. */
    public void markRefunded(long refundAmount, boolean restock, Long changedBy) {
        requireType(ReturnType.RETURN);
        this.refundAmount = refundAmount;
        this.restock = restock;
        transition(ReturnStatus.INSPECTED, ReturnStatus.REFUNDED, changedBy, null);
    }

    /** 교환 확정: INSPECTED → COMPLETED (EXCHANGE 전용). 재출고 shipment 연결. */
    public void markExchanged(Long exchangeShipmentId, Long changedBy) {
        requireType(ReturnType.EXCHANGE);
        this.exchangeShipmentId = exchangeShipmentId;
        transition(ReturnStatus.INSPECTED, ReturnStatus.COMPLETED, changedBy, null);
    }

    /** 이 요청이 주어진 셀러 것인지 — null(플랫폼) null-safe(인가). */
    public boolean belongsToSeller(Long sellerId) {
        return Objects.equals(this.sellerId, sellerId);
    }

    /** 종료 상태(더 이상 전이 없음) — 중복 반품 가드에서 "미종료"만 막는다. */
    public boolean isTerminal() {
        return status == ReturnStatus.REFUNDED || status == ReturnStatus.COMPLETED || status == ReturnStatus.REJECTED;
    }

    private void transition(ReturnStatus from, ReturnStatus next, Long changedBy, String memo) {
        if (status != from) {
            throw invalid(next);
        }
        setStatus(next, changedBy, memo);
    }

    private void setStatus(ReturnStatus next, Long changedBy, String memo) {
        ReturnStatus from = this.status;
        this.status = next;
        recordHistory(from, next, changedBy, memo);
    }

    private void requireType(ReturnType expected) {
        if (this.type != expected) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이 전이는 " + expected + " 요청에만 허용됩니다. (현재: " + this.type + ")");
        }
    }

    private BusinessException invalid(ReturnStatus next) {
        return new BusinessException(HttpStatus.CONFLICT,
                "반품 상태를 " + this.status + "에서 " + next + "(으)로 변경할 수 없습니다.");
    }

    private void recordHistory(ReturnStatus from, ReturnStatus to, Long changedBy, String memo) {
        this.statusHistory.add(ReturnStatusHistory.of(this, from, to, changedBy, memo));
    }
}
