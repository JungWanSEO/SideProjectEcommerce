package com.commerce.api.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.config.JpaConfig;
import com.commerce.api.global.config.QuerydslConfig;
import com.commerce.api.product.dto.LowStockOption;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ProductRepository 슬라이스 테스트 (@DataJpaTest).
 */
@DataJpaTest
@Import({JpaConfig.class, QuerydslConfig.class})   // QuerydslConfig: search()가 쓰는 JPAQueryFactory 빈 제공
class ProductRepositoryTest {

    /** 검색 테스트에서 노출 대상으로 보는 상태(판매중·품절). */
    private static final List<ProductStatus> VISIBLE =
            List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    @Autowired
    private ProductRepository productRepository;

    private Product newProduct() {
        Product product = Product.builder()
                .name("반팔티셔츠")
                .price(29000L)
                .description("면 100%")
                .status(ProductStatus.ON_SALE)
                .build();
        product.addOption(ProductOption.create("M", 100));   // 재고는 옵션에
        return product;
    }

    private Product product(String name, ProductStatus status) {
        return Product.builder()
                .name(name)
                .price(29000L)
                .description("desc")
                .status(status)
                .build();
    }

    @Test
    @DisplayName("저장 시 id와 createdAt이 자동으로 채워진다")
    void save_autoFields() {
        Product saved = productRepository.save(newProduct());
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("저장한 상품의 모든 필드가 그대로 복원된다 (enum 포함)")
    void save_persistsAllFields() {
        Product saved = productRepository.save(newProduct());

        Product found = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("반팔티셔츠");
        assertThat(found.getPrice()).isEqualTo(29000L);
        assertThat(found.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(found.getOptions()).hasSize(1);                       // 옵션이 cascade로 함께 저장됨
        assertThat(found.getOptions().get(0).getSize()).isEqualTo("M");
        assertThat(found.getOptions().get(0).getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("findById - 없는 id면 빈 Optional")
    void findById_notFound() {
        assertThat(productRepository.findById(999L)).isEmpty();
    }

    private Product productPriced(String name, long price, ProductStatus status) {
        return Product.builder()
                .name(name).price(price).description("desc").status(status).build();
    }

    @Test
    @DisplayName("search - 키워드(상품명 부분일치) + 가시 상태(판매중·품절)만 조회한다")
    void search_keywordAndVisibility() {
        productRepository.save(product("반팔티셔츠", ProductStatus.ON_SALE));
        productRepository.save(product("긴팔티셔츠", ProductStatus.SOLD_OUT));
        productRepository.save(product("청바지", ProductStatus.ON_SALE));            // 키워드 불일치
        productRepository.save(product("판매중지티셔츠", ProductStatus.DISCONTINUED)); // 상태 제외

        Page<Product> page = productRepository.search(VISIBLE,
                new ProductSearchCondition("티셔츠", null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).extracting(Product::getName)
                .containsExactlyInAnyOrder("반팔티셔츠", "긴팔티셔츠");
    }

    @Test
    @DisplayName("search - 가격대(min~max)로 거른다")
    void search_priceRange() {
        productRepository.save(productPriced("A", 10000L, ProductStatus.ON_SALE));
        productRepository.save(productPriced("B", 20000L, ProductStatus.ON_SALE));
        productRepository.save(productPriced("C", 30000L, ProductStatus.ON_SALE));

        Page<Product> page = productRepository.search(VISIBLE,
                new ProductSearchCondition(null, 15000L, 25000L, null, null, null),   // 15000 이상 ~ 25000 이하
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).extracting(Product::getName).containsExactly("B");
    }

    @Test
    @DisplayName("search - 조건이 모두 비면 가시 상태 전체를 페이지로 (paging 메타)")
    void search_emptyConditionPaging() {
        for (int i = 0; i < 3; i++) {
            productRepository.save(product("상품" + i, ProductStatus.ON_SALE));
        }

        Page<Product> first = productRepository.search(VISIBLE,
                new ProductSearchCondition(null, null, null, null, null, null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
    }

    @Test
    @DisplayName("search - categoryId로 거른다 (Long FK 컬럼 기준)")
    void search_byCategory() {
        productRepository.save(Product.builder().name("상의A").price(10000L)
                .description("d").status(ProductStatus.ON_SALE).categoryId(1L).build());
        productRepository.save(Product.builder().name("하의B").price(10000L)
                .description("d").status(ProductStatus.ON_SALE).categoryId(2L).build());

        Page<Product> page = productRepository.search(VISIBLE,
                new ProductSearchCondition(null, null, null, 1L, null, null),   // categoryId=1만
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).extracting(Product::getName).containsExactly("상의A");
    }

    @Test
    @DisplayName("search - optionSize: 그 사이즈를 재고>0으로 가진 상품만 (M 품절은 제외)")
    void search_byOptionSize() {
        Product hasM = product("M있음", ProductStatus.ON_SALE);
        hasM.addOption(ProductOption.create("M", 5));
        Product onlyL = product("L만", ProductStatus.ON_SALE);
        onlyL.addOption(ProductOption.create("L", 5));
        Product mSoldOut = product("M품절", ProductStatus.ON_SALE);
        mSoldOut.addOption(ProductOption.create("M", 0));   // M이지만 재고0 → 제외
        productRepository.save(hasM);
        productRepository.save(onlyL);
        productRepository.save(mSoldOut);

        Page<Product> page = productRepository.search(VISIBLE,
                new ProductSearchCondition(null, null, null, null, null, "M"),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).extracting(Product::getName).containsExactly("M있음");
    }

    @Test
    @DisplayName("search - 평점 높은순(ratingAverage desc): 평균 내림차순, 리뷰 없는 상품은 맨 뒤")
    void search_sortByRatingAverage() {
        productRepository.save(rated("평균4.0", 2, 8));    // 8/2 = 4.0
        productRepository.save(rated("평균5.0", 1, 5));    // 5/1 = 5.0
        productRepository.save(rated("리뷰없음", 0, 0));   // count=0 → 맨 뒤

        Page<Product> page = productRepository.search(VISIBLE,
                new ProductSearchCondition(null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "ratingAverage")));

        assertThat(page.getContent()).extracting(Product::getName)
                .containsExactly("평균5.0", "평균4.0", "리뷰없음");
    }

    @Test
    @DisplayName("findFeed - 커서 미만 id를 최신순(desc)으로, 비노출 상태는 제외")
    void findFeed_cursorPaging() {
        Long id1 = productRepository.save(product("p1", ProductStatus.ON_SALE)).getId();
        Long id2 = productRepository.save(product("p2", ProductStatus.ON_SALE)).getId();
        Long id3 = productRepository.save(product("p3", ProductStatus.ON_SALE)).getId();
        productRepository.save(product("숨김", ProductStatus.DISCONTINUED));   // 노출 제외

        // 첫 페이지(cursor=null) size 2 → 최신 2개(id desc)
        List<Product> first = productRepository.findFeed(VISIBLE, null, PageRequest.of(0, 2));
        assertThat(first).extracting(Product::getId).containsExactly(id3, id2);

        // 다음 페이지(cursor=id2) → id2 미만만(숨김 상태는 애초에 제외)
        List<Product> next = productRepository.findFeed(VISIBLE, id2, PageRequest.of(0, 10));
        assertThat(next).extracting(Product::getId).containsExactly(id1);
    }

    /** 평점 카운터를 직접 세팅한 상품(엔티티에 setter가 없어 리플렉션 사용). */
    private Product rated(String name, int ratingCount, int ratingSum) {
        Product p = product(name, ProductStatus.ON_SALE);
        ReflectionTestUtils.setField(p, "ratingCount", ratingCount);
        ReflectionTestUtils.setField(p, "ratingSum", ratingSum);
        return p;
    }

    // === 재고 임박·품절 리포트 (옵션 단위) ===

    /** 사이즈별 재고를 지정한 상품. (재고는 상품이 아니라 옵션에 있다.) */
    private Product withStocks(String name, ProductStatus status, int... stocks) {
        Product p = product(name, status);
        for (int i = 0; i < stocks.length; i++) {
            p.addOption(ProductOption.create("SIZE" + i, stocks[i]));
        }
        return p;
    }

    @Test
    @DisplayName("findLowStockOptions - 임계치 이하 옵션만, 재고 적은 순(품절이 맨 위) / 판매중지 상품은 제외")
    void findLowStockOptions_thresholdAndOrder() {
        productRepository.save(withStocks("니트", ProductStatus.ON_SALE, 0, 3, 50));    // 0·3만 대상(50은 여유)
        productRepository.save(withStocks("코트", ProductStatus.SOLD_OUT, 2));          // 품절 상품도 재입고 대상
        productRepository.save(withStocks("단종옷", ProductStatus.DISCONTINUED, 0));     // 판매중지 → 제외

        List<LowStockOption> items = productRepository.findLowStockOptions(VISIBLE, 5, 10);

        assertThat(items).extracting(LowStockOption::stock).containsExactly(0, 2, 3);   // 재고 오름차순
        assertThat(items).extracting(LowStockOption::productName)
                .containsExactly("니트", "코트", "니트");
        assertThat(items).noneMatch(i -> i.productStatus() == ProductStatus.DISCONTINUED);
    }

    @Test
    @DisplayName("findLowStockOptions - limit으로 상위 N건만")
    void findLowStockOptions_limit() {
        productRepository.save(withStocks("니트", ProductStatus.ON_SALE, 0, 1, 2, 3));

        List<LowStockOption> items = productRepository.findLowStockOptions(VISIBLE, 5, 2);

        assertThat(items).extracting(LowStockOption::stock).containsExactly(0, 1);
    }

    @Test
    @DisplayName("countOptionsWithStockBetween - 품절(0,0)과 임박(1,threshold)을 구간으로 센다")
    void countOptionsWithStockBetween_bands() {
        productRepository.save(withStocks("니트", ProductStatus.ON_SALE, 0, 0, 3, 50));
        productRepository.save(withStocks("단종옷", ProductStatus.DISCONTINUED, 0));   // 세지 않는다

        assertThat(productRepository.countOptionsWithStockBetween(VISIBLE, 0, 0)).isEqualTo(2);   // 품절 2
        assertThat(productRepository.countOptionsWithStockBetween(VISIBLE, 1, 5)).isEqualTo(1);   // 임박 1(재고 3)
    }
}