package com.commerce.api.returns.entity;

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
 * 반품 상태 이력 1건 — {@link ReturnRequest} 애그리거트 내부(ShipmentStatusHistory 동형).
 *
 * <p>전이마다 {@link ReturnRequest}가 append한다("전이하면 반드시 흔적"). append-only라 수정 메서드 없음.
 * {@code from_status}는 생성 시점(REQUESTED) null. {@code changed_by}는 주체(구매자/셀러/ADMIN 회원 ID).
 */
@Getter
@Entity
@Table(name = "return_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnStatusHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_request_id", nullable = false)
    private ReturnRequest returnRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ReturnStatus fromStatus;   // 생성 시 null

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ReturnStatus toStatus;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(length = 255)
    private String memo;

    private ReturnStatusHistory(ReturnRequest returnRequest, ReturnStatus fromStatus, ReturnStatus toStatus,
                                Long changedBy, String memo) {
        this.returnRequest = returnRequest;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.memo = memo;
    }

    static ReturnStatusHistory of(ReturnRequest returnRequest, ReturnStatus fromStatus, ReturnStatus toStatus,
                                  Long changedBy, String memo) {
        return new ReturnStatusHistory(returnRequest, fromStatus, toStatus, changedBy, memo);
    }
}
