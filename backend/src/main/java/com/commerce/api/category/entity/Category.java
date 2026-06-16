package com.commerce.api.category.entity;

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
 * 상품 카테고리 (예: 상의, 하의, 신발). <b>2단계 계층</b>(부모→자식) — parentId로 표현(V33).
 *
 * <p>별도 애그리거트다. 다른 도메인(Product 등)은 객체 연관이 아니라 <b>categoryId(Long)</b>로
 * 참조한다(architecture.md §11). 부모도 같은 발상으로 <b>parentId(Long)</b> ID 참조(객체 연관 없음).
 */
@Getter
@Entity
@Table(name = "category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA가 쓰는 기본 생성자(외부 직접 생성은 막음)
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** 부모 카테고리 ID(null=최상위). 다른 행(카테고리)을 ID로 참조 — 2단계까지만(자식 밑 자식 금지, 서비스 검증). */
    private Long parentId;

    private Category(String name, Long parentId) {
        this.name = name;
        this.parentId = parentId;
    }

    /** 최상위 카테고리 생성. */
    public static Category create(String name) {
        return new Category(name, null);
    }

    /** 자식 카테고리 생성(부모 ID 지정). parentId=null이면 최상위와 동일. */
    public static Category create(String name, Long parentId) {
        return new Category(name, parentId);
    }
}
