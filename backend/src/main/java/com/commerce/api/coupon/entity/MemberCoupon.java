package com.commerce.api.coupon.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회원에게 발급된 쿠폰(쿠폰함의 한 장). 발급형(ISSUED) 쿠폰의 보유·단일 사용을 추적한다.
 *
 * <p>회원·쿠폰 모두 다른 애그리거트라 <b>ID 참조</b>(memberId·couponId) — 객체 연관 아님(architecture.md §11).
 * 회원·쿠폰당 1장(UNIQUE) — 같은 쿠폰을 두 번 발급하지 않는다. 사용하면 USED로 잠기고(단일 사용),
 * 주문 취소 시 service가 release로 UNUSED로 되돌린다("취소했는데 쿠폰 날림" 방지).
 */
@Getter
@Entity
@Table(name = "member_coupon",
        uniqueConstraints = @UniqueConstraint(name = "UK_member_coupon", columnNames = {"member_id", "coupon_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCoupon extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberCouponStatus status;

    /** 사용 일시(미사용이면 null). 발급 일시는 BaseEntity.createdAt. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    private MemberCoupon(Long memberId, Long couponId) {
        this.memberId = memberId;
        this.couponId = couponId;
        this.status = MemberCouponStatus.UNUSED;
    }

    /** 회원에게 쿠폰 발급(미사용 상태). */
    public static MemberCoupon issue(Long memberId, Long couponId) {
        return new MemberCoupon(memberId, couponId);
    }

    /** 사용 처리(체크아웃 시) — 이미 사용했으면 409. 단일 사용 잠금. */
    public void markUsed() {
        if (this.status == MemberCouponStatus.USED) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 사용한 쿠폰입니다.");
        }
        this.status = MemberCouponStatus.USED;
        this.usedAt = LocalDateTime.now();
    }

    /** 사용 취소(주문 취소 시 복원) — 사용 상태였으면 미사용으로 되돌린다. */
    public void release() {
        this.status = MemberCouponStatus.UNUSED;
        this.usedAt = null;
    }

    public boolean isUnused() {
        return this.status == MemberCouponStatus.UNUSED;
    }
}
