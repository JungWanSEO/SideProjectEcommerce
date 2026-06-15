package com.commerce.api.product.entity;

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
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 상품 엔티티 (product 테이블).
 *
 * - 가격(price)은 long(원 단위). status는 @Enumerated(STRING), 삭제 대신 상태로 관리.
 * - <b>재고는 상품이 아니라 옵션(사이즈) 단위</b> — {@link ProductOption}이 보유(사이즈=SKU).
 *   따라서 stock/@Version·재고 메서드가 ProductOption으로 내려갔다.
 * - 카테고리·브랜드는 ID 참조(Long). 옵션은 애그리거트 내부 객체 연관(@OneToMany).
 */
@Getter
@Entity
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;        // 원 단위

    @Column(length = 1000)
    private String description;

    /**
     * 대표 이미지 URL (nullable). 로컬 정적 자산 경로("/products/3.svg")나 외부 URL을 담는다.
     * 갤러리(여러 장)는 후속 — 지금은 단일 대표 1장만(플랜의 '과투자 금지'). 비어 있으면 FE가 결정적 placeholder로 폴백.
     */
    @Column(length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    private Long categoryId;   // 카테고리 참조(ID, nullable)
    private Long brandId;      // 브랜드 참조(ID, nullable)

    /**
     * 평점 비정규화 카운터(리뷰 도메인이 원자 UPDATE로 갱신). 평균 = ratingSum/ratingCount.
     * 읽기(목록·상세)에서 매번 리뷰를 집계하지 않으려고 상품에 누적해 둔다. 작성/삭제 시점에만 증감.
     */
    @Column(nullable = false)
    private int ratingCount = 0;

    @Column(nullable = false)
    private int ratingSum = 0;

    /**
     * 찜(위시리스트) 비정규화 카운터(wishlist 도메인이 원자 UPDATE로 갱신). = 이 상품을 찜한 회원 수.
     * 인기도 신호 — 목록 정렬(인기순)·추천의 입력으로 쓴다. 찜할 때마다 wishlist를 COUNT 하지 않으려고
     * 상품에 누적해 둔다(rating 카운터와 같은 패턴). 추가/해제 시점에만 +1/−1.
     */
    @Column(nullable = false)
    private int wishlistCount = 0;

    /** 사이즈 옵션들(애그리거트 내부). 재고·@Version은 각 옵션이 보유. */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductOption> options = new ArrayList<>();

    @Builder
    private Product(String name, long price, String description, String imageUrl, ProductStatus status,
                    Long categoryId, Long brandId) {
        this.name = name;
        this.price = price;
        this.description = description;
        this.imageUrl = imageUrl;
        this.status = status;
        this.categoryId = categoryId;
        this.brandId = brandId;
    }

    /** 평점 평균(소수 1자리). 리뷰가 없으면 0. (비정규화 카운터에서 계산 — 별도 집계 쿼리 불필요) */
    public double getRatingAverage() {
        return ratingCount == 0 ? 0.0 : Math.round((double) ratingSum / ratingCount * 10) / 10.0;
    }

    /** 옵션 추가 + 양방향 연관 설정. */
    public void addOption(ProductOption option) {
        options.add(option);
        option.assignProduct(this);
    }

    /** 특정 옵션 재고 차감 (주문 시). 애그리거트 루트를 통해 옵션에 위임. */
    public void decreaseStock(Long optionId, int quantity) {
        findOption(optionId).decreaseStock(quantity);
    }

    /** 특정 옵션 재고 복원 (주문 취소 시). */
    public void increaseStock(Long optionId, int quantity) {
        findOption(optionId).increaseStock(quantity);
    }

    /** 옵션(사이즈) 라벨 조회 — 주문 시점 사이즈 스냅샷용. */
    public String optionSize(Long optionId) {
        return findOption(optionId).getSize();
    }

    private ProductOption findOption(Long optionId) {
        return options.stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "옵션을 찾을 수 없습니다. (id: " + optionId + ")"));
    }
}
