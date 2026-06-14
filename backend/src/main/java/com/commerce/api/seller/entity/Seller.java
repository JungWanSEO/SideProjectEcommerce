package com.commerce.api.seller.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 셀러(입점사) — 플랫폼에 입점한 판매 주체.
 *
 * <p>Phase 2 "셀러별 정산"의 정산 수취 주체다. 한 셀러가 여러 브랜드를 운영할 수 있다
 * (seller 1:N brand). 브랜드는 {@code Brand.sellerId}(ID 참조)로 셀러에 귀속된다 —
 * 별도 애그리거트라 FK·객체 연관을 두지 않는다(architecture.md §11).
 *
 * <p>{@code commissionRate}(플랫폼 판매수수료율)는 정산 시점에 SettlementEntry로
 * 스냅샷되어 "그때 몇 %"가 보존될 예정이다(Step 2). 가격 스냅샷과 같은 이력 보존 철학.
 */
@Getter
@Entity
@Table(name = "seller")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /**
     * 플랫폼 판매수수료율 (예: 0.10 = 매출의 10%를 플랫폼이 수취).
     * PG 수수료(결제 대행 비용)와는 별개 차원의 비용이다. 돈(원)이 아니라 비율이고
     * 합산하지 않으므로 double로 둔다(money는 long).
     */
    @Column(name = "commission_rate", nullable = false)
    private double commissionRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SellerStatus status;

    /** 정산금 입금 계좌(운영 메타, nullable). 정산 금액 계산엔 직접 관여하지 않는다. */
    @Column(name = "payout_account", length = 100)
    private String payoutAccount;

    /** 사업자등록번호(운영 메타, nullable). */
    @Column(name = "business_number", length = 20)
    private String businessNumber;

    private Seller(String name, double commissionRate, String payoutAccount, String businessNumber) {
        this.name = name;
        this.commissionRate = commissionRate;
        this.payoutAccount = payoutAccount;
        this.businessNumber = businessNumber;
        this.status = SellerStatus.ACTIVE;   // 입점 시 기본 활성
    }

    /** 신규 셀러 생성(상태=ACTIVE). */
    public static Seller create(String name, double commissionRate, String payoutAccount,
            String businessNumber) {
        return new Seller(name, commissionRate, payoutAccount, businessNumber);
    }

    /** 기본 정보 수정(상태 전이는 별도 메서드 suspend/activate로). */
    public void update(String name, double commissionRate, String payoutAccount, String businessNumber) {
        this.name = name;
        this.commissionRate = commissionRate;
        this.payoutAccount = payoutAccount;
        this.businessNumber = businessNumber;
    }

    /** 입점 정지(이미 정지면 409). */
    public void suspend() {
        if (this.status == SellerStatus.SUSPENDED) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 정지된 셀러입니다.");
        }
        this.status = SellerStatus.SUSPENDED;
    }

    /** 입점 재개(이미 활성이면 409). */
    public void activate() {
        if (this.status == SellerStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 활성 상태인 셀러입니다.");
        }
        this.status = SellerStatus.ACTIVE;
    }
}
