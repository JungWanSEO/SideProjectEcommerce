package com.commerce.api.product.repository;

import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상품 DB 접근.
 *
 * <p>기본 CRUD는 {@link JpaRepository}가 제공한다.
 * 동적 검색/필터는 {@link ProductRepositoryCustom}(QueryDSL 구현)을 함께 상속해 사용한다.
 */
public interface ProductRepository extends JpaRepository<Product, Long>, ProductRepositoryCustom {

    /**
     * 옵션 ID로 그 옵션이 속한 상품(애그리거트 루트)을 조회한다.
     * 주문 시 "루트 경유" 재고 차감에 사용 — 반환된 Product의 options에서 해당 옵션을 찾아 차감한다.
     */
    @Query("select p from Product p join p.options o where o.id = :optionId")
    Optional<Product> findByOptionId(@Param("optionId") Long optionId);

    /** 이 카테고리를 참조하는 상품이 하나라도 있는지 — 카테고리 삭제 가드(참조 중이면 409). */
    boolean existsByCategoryId(Long categoryId);

    /** 특정 상태 상품 수 — 대시보드 "판매 중 상품" KPI(ON_SALE). */
    long countByStatus(ProductStatus status);

    /**
     * 커서 기반 피드 — 노출 상태 상품을 id 내림차순(최신순)으로, {@code cursor} 미만 id만(첫 페이지면 cursor=null).
     * Pageable로 개수를 제한한다. offset 없이 인덱스 탐색이라 페이지 깊이와 무관하게 빠르다.
     */
    @Query("select p from Product p where p.status in :statuses "
            + "and (:cursor is null or p.id < :cursor) order by p.id desc")
    List<Product> findFeed(@Param("statuses") Collection<ProductStatus> statuses,
            @Param("cursor") Long cursor, Pageable pageable);

    /** 이 브랜드를 참조하는 상품이 하나라도 있는지 — 브랜드 삭제 가드(참조 중이면 409). */
    boolean existsByBrandId(Long brandId);

    /**
     * 인기 상품 상위 12개 — 추천 콜드스타트 폴백(행동 이력이 없는 회원/비로그인은 전체 인기순으로 채운다).
     * 인기 = 찜 수 우선, 동률은 리뷰 수. ON_SALE만.
     */
    List<Product> findTop12ByStatusOrderByWishlistCountDescRatingCountDesc(ProductStatus status);

    /**
     * "함께 산 상품" 콜드스타트 폴백 — co-occurrence 데이터가 없을 때 같은 <b>카테고리 또는 브랜드</b>의
     * ON_SALE 상품을 인기순(찜 수→리뷰 수)으로. 기준 상품 자신은 제외.
     *
     * <p>categoryId/brandId가 null이면 그 조건은 SQL의 {@code = null} 비교라 매칭되지 않는다(자연스럽게 무시됨).
     * 둘 다 null인 상품(카테고리·브랜드 미지정)이면 결과가 비고, 서비스가 전체 인기순으로 한 번 더 폴백한다.
     */
    @Query("select p from Product p where p.id <> :excludeId and p.status = :status "
            + "and (p.categoryId = :categoryId or p.brandId = :brandId) "
            + "order by p.wishlistCount desc, p.ratingCount desc")
    List<Product> findCoOccurrenceFallback(@Param("categoryId") Long categoryId, @Param("brandId") Long brandId,
            @Param("excludeId") Long excludeId, @Param("status") ProductStatus status, Pageable pageable);

    /**
     * 평점 카운터 증가(리뷰 작성 시). <b>원자 UPDATE</b> — 엔티티 더티체킹 대신 DB에서 직접 증감해
     * 동시 작성 시 lost update를 막는다(Product에 @Version 없이도 안전). 평균은 읽을 때 sum/count로 계산.
     *
     * <p>flushAutomatically=true: 같은 트랜잭션에 보류된 엔티티 변경(리뷰 INSERT/DELETE)을 이 벌크 UPDATE
     * <b>전에 flush</b>한다. (안 하면 뒤따르는 clearAutomatically가 flush 안 된 변경을 버린다 — 리뷰 삭제 누락 함정.)
     * clearAutomatically=true: 벌크 UPDATE 후 컨텍스트를 비워, 이후 같은 tx에서 읽을 때 stale 엔티티를 막는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.ratingCount = p.ratingCount + 1, p.ratingSum = p.ratingSum + :rating "
            + "where p.id = :productId")
    void incrementRating(@Param("productId") Long productId, @Param("rating") int rating);

    /** 평점 카운터 감소(리뷰 삭제 시). count가 0 아래로 내려가지 않도록 가드. (flush/clear 이유는 increment 참고) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.ratingCount = p.ratingCount - 1, p.ratingSum = p.ratingSum - :rating "
            + "where p.id = :productId and p.ratingCount > 0")
    void decrementRating(@Param("productId") Long productId, @Param("rating") int rating);

    /** 평점 합계만 델타 조정(리뷰 수정 시 평점이 바뀐 경우). count는 그대로. (flush/clear 이유는 increment 참고) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.ratingSum = p.ratingSum + :delta where p.id = :productId")
    void adjustRatingSum(@Param("productId") Long productId, @Param("delta") int delta);

    /** 찜 카운터 증가(찜 추가 시). 원자 UPDATE로 동시 찜의 lost update 방지. (flush/clear 이유는 incrementRating 참고) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.wishlistCount = p.wishlistCount + 1 where p.id = :productId")
    void incrementWishlist(@Param("productId") Long productId);

    /** 찜 카운터 감소(찜 해제 시). 0 아래로 내려가지 않도록 가드. (flush/clear 이유는 incrementRating 참고) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.wishlistCount = p.wishlistCount - 1 "
            + "where p.id = :productId and p.wishlistCount > 0")
    void decrementWishlist(@Param("productId") Long productId);
}
