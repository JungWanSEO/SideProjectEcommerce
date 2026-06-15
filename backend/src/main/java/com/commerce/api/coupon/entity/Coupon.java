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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 쿠폰(프로모션) — 코드 입력형. 발급된 한 행을 여러 주문이 코드로 사용한다.
 *
 * <p>설계 축(사용자 합의):
 * <ul>
 *   <li>할인 종류 = {@link DiscountType} 정액/정률(상한 {@code maxDiscountAmount}).
 *   <li>적용 범위 = {@code sellerId} null이면 플랫폼 와이드(주문 전체), 값이면 그 셀러 상품 소계에만.
 *   <li>분담 주체 = {@link CouponFundedBy} 플랫폼/셀러 — 정산 분해(Step 2)에서 셀러 실수령/플랫폼 손익에 반영.
 * </ul>
 *
 * <p>여러 행에 걸친 규칙(코드 유일성 등)은 {@code CouponService}가, 한 쿠폰의 사용 가능 여부/할인 계산은
 * 엔티티가 책임진다(Address와 같은 분담 — 행 사이 규칙은 서비스, 한 행의 상태/행위는 엔티티).
 */
@Getter
@Entity
@Table(name = "coupon")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 고객이 입력하는 코드(대문자 정규화해 저장·조회). 유일. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    /** 운영/표시용 이름(예: "신규가입 5천원 쿠폰"). */
    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    /** 정액=깎을 금액(원), 정률=퍼센트(1~100). 비율/금액을 한 컬럼에 담되 의미는 discountType이 결정. */
    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    /** 정률 할인 상한(원). 정액이거나 무제한이면 null. */
    @Column(name = "max_discount_amount")
    private Long maxDiscountAmount;

    /** 최소 적용 대상 금액(원). 적용 대상 금액이 이 값 이상이어야 사용 가능. */
    @Column(name = "min_order_amount", nullable = false)
    private long minOrderAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "funded_by", nullable = false, length = 20)
    private CouponFundedBy fundedBy;

    /** 배포 방식: PUBLIC(코드 입력·무제한) / ISSUED(회원 발급·지갑·단일 사용). */
    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 20)
    private CouponIssueType issueType;

    /** 적용 범위: null=플랫폼 와이드(주문 전체), 값=해당 셀러 상품에만(셀러 한정). 다른 애그리거트 → ID 참조. */
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponStatus status;

    private Coupon(String code, String name, DiscountType discountType, long discountValue,
            Long maxDiscountAmount, long minOrderAmount, CouponFundedBy fundedBy, Long sellerId,
            CouponIssueType issueType, LocalDateTime validFrom, LocalDateTime validUntil) {
        // 한 쿠폰 안에서 닫히는 불변식은 엔티티가 지킨다(교차필드라 DTO 어노테이션으로 표현하기 어려운 것들).
        if (validUntil.isBefore(validFrom)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "쿠폰 유효기간이 올바르지 않습니다(시작 > 종료).");
        }
        if (discountValue <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "할인 값은 0보다 커야 합니다.");
        }
        if (discountType == DiscountType.PERCENTAGE && discountValue > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "정률 할인은 100% 이하여야 합니다.");
        }
        this.code = code;
        this.name = name;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.maxDiscountAmount = maxDiscountAmount;
        this.minOrderAmount = minOrderAmount;
        this.fundedBy = fundedBy;
        this.sellerId = sellerId;
        this.issueType = issueType != null ? issueType : CouponIssueType.PUBLIC;   // 미지정 시 공개형
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.status = CouponStatus.ACTIVE;   // 발급 시 활성
    }

    /** 신규 쿠폰 발급(상태=ACTIVE). 코드 유일성은 CouponService가 보장. */
    public static Coupon create(String code, String name, DiscountType discountType, long discountValue,
            Long maxDiscountAmount, long minOrderAmount, CouponFundedBy fundedBy, Long sellerId,
            CouponIssueType issueType, LocalDateTime validFrom, LocalDateTime validUntil) {
        return new Coupon(code, name, discountType, discountValue, maxDiscountAmount, minOrderAmount,
                fundedBy, sellerId, issueType, validFrom, validUntil);
    }

    /** 발급형(ISSUED) 쿠폰인지 — 지갑 보유·단일 사용이 필요한 쿠폰. */
    public boolean isIssued() {
        return this.issueType == CouponIssueType.ISSUED;
    }

    /** 운영자 비활성화(기간이 남아도 즉시 사용 불가). */
    public void disable() {
        this.status = CouponStatus.DISABLED;
    }

    /**
     * 적용 대상 금액에 대해 사용 가능한지 검증한다. 불가하면 BusinessException(400).
     * (적용 대상 금액 = 플랫폼 쿠폰이면 주문 총액, 셀러 쿠폰이면 그 셀러 상품 소계 합 — CouponService가 산정.)
     */
    public void validateUsable(long applicableAmount, LocalDateTime now) {
        if (status != CouponStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "사용할 수 없는 쿠폰입니다.");
        }
        if (now.isBefore(validFrom) || now.isAfter(validUntil)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "쿠폰 사용 기간이 아닙니다.");
        }
        if (applicableAmount <= 0) {
            // 셀러 한정 쿠폰인데 그 셀러 상품이 주문에 없을 때 등.
            throw new BusinessException(HttpStatus.BAD_REQUEST, "이 쿠폰을 적용할 수 있는 상품이 없습니다.");
        }
        if (applicableAmount < minOrderAmount) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "최소 주문금액(" + minOrderAmount + "원) 이상부터 사용할 수 있는 쿠폰입니다.");
        }
    }

    /** 적용 대상 금액에 대해 깎을 금액(원)을 계산한다. 0 이상, applicableAmount 이하. */
    public long calculateDiscount(long applicableAmount) {
        return discountType.discountFor(applicableAmount, discountValue, maxDiscountAmount);
    }
}
