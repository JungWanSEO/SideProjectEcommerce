package com.commerce.api.product.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 상품 옵션 (사이즈). Product 애그리거트 내부 — OrderItem↔Order와 동형(객체 연관).
 *
 * <p>재고는 옵션 단위로 관리한다(사이즈 = SKU). 동시 차감 충돌은 이 엔티티의 {@code @Version}
 * 낙관적 락으로 감지한다(예전엔 Product.stock에 있던 책임이 여기로 내려옴).
 */
@Getter
@Entity
@Table(name = "product_option")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;   // 소속 상품(같은 애그리거트 내부 역참조)

    @Column(nullable = false, length = 30)
    private String size;       // 예: "S"/"M"/"L", "FREE", "260"

    @Column(nullable = false)
    private int stock;

    /** 낙관적 락 버전. UPDATE 시 WHERE version=? 로 동시 재고 차감 충돌을 감지한다. */
    @Version
    private Long version;

    private ProductOption(String size, int stock) {
        this.size = size;
        this.stock = stock;
    }

    /** 정적 팩토리. 상품에는 Product.addOption으로 붙인다. */
    public static ProductOption create(String size, int stock) {
        return new ProductOption(size, stock);
    }

    /** 양방향 연관 설정 (Product.addOption에서 호출). */
    void assignProduct(Product product) {
        this.product = product;
    }

    /** 재고 차감 (주문 시). 부족하면 409. 동시 차감 충돌은 @Version으로 감지 → 상위에서 재시도. */
    public void decreaseStock(int quantity) {
        if (quantity > this.stock) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "재고가 부족합니다. (사이즈: " + size + ", 남은 재고: " + stock + ")");
        }
        this.stock -= quantity;
    }

    /** 재고 복원 (주문 취소 시). */
    public void increaseStock(int quantity) {
        this.stock += quantity;
    }

    /** 옵션 정보 수정 (사이즈/재고 절대값). 관리자 옵션 수정용. */
    public void update(String size, int stock) {
        this.size = size;
        this.stock = stock;
    }

    /** 품절 여부(사이즈별 품절 표시용). */
    public boolean isSoldOut() {
        return this.stock <= 0;
    }
}
