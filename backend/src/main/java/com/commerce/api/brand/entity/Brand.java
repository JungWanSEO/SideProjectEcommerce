package com.commerce.api.brand.entity;

import com.commerce.api.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 브랜드 (예: Nike, Adidas).
 *
 * <p>별도 애그리거트다. 다른 도메인(Product 등)은 객체 연관이 아니라 <b>brandId(Long)</b>로
 * 참조한다(architecture.md §11 "애그리거트 간 ID 참조 · 객체 연관 금지").
 */
@Getter
@Entity
@Table(name = "brand")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Brand extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /**
     * 셀러(입점사) 참조(ID, nullable). 한 셀러가 여러 브랜드를 운영(seller 1:N brand).
     * null이면 미귀속(플랫폼 직매입). 별도 애그리거트라 객체 연관이 아닌 ID로 참조한다.
     */
    @Column(name = "seller_id")
    private Long sellerId;

    private Brand(String name) {
        this.name = name;
    }

    public static Brand create(String name) {
        return new Brand(name);
    }

    /** 셀러에 귀속(또는 null로 귀속 해제). */
    public void assignSeller(Long sellerId) {
        this.sellerId = sellerId;
    }
}
