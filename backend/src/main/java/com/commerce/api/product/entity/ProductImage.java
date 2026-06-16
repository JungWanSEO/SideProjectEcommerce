package com.commerce.api.product.entity;

import com.commerce.api.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 상품 이미지 (갤러리 한 장). Product 애그리거트 내부 — {@link ProductOption}과 동형(객체 연관).
 *
 * <p>대표 1장은 {@code Product.imageUrl}이 유지하고, 추가 이미지(갤러리)를 이 테이블이 담는다.
 * {@code sortOrder}로 노출 순서를 정한다(0부터, 추가 시 뒤에 붙음).
 */
@Getter
@Entity
@Table(name = "product_image")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;   // 소속 상품(같은 애그리거트 내부 역참조)

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;     // 노출 순서(오름차순)

    private ProductImage(String url, int sortOrder) {
        this.url = url;
        this.sortOrder = sortOrder;
    }

    /** 정적 팩토리. 상품에는 Product.addImage로 붙는다. */
    public static ProductImage create(String url, int sortOrder) {
        return new ProductImage(url, sortOrder);
    }

    /** 양방향 연관 설정 (Product.addImage에서 호출). */
    void assignProduct(Product product) {
        this.product = product;
    }
}
