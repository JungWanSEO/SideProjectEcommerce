package com.commerce.api.product.service;

import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.category.entity.Category;
import com.commerce.api.category.repository.CategoryRepository;
import com.commerce.api.global.config.CacheConfig;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductCreateRequest;
import com.commerce.api.product.dto.ProductCursorResponse;
import com.commerce.api.product.dto.ProductImageCreateRequest;
import com.commerce.api.product.dto.ProductOptionUpsertRequest;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.dto.ProductStatusUpdateRequest;
import com.commerce.api.product.dto.ProductUpdateRequest;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 비즈니스 로직. 등록 / 단건 조회 / 검색.
 *
 * <p>Product는 카테고리·브랜드를 ID(Long)로만 참조한다(architecture.md §11 ID 참조 원칙).
 * 응답에 이름이 필요하면 여기서 카테고리/브랜드를 조회해 채운다(enrich) — Cart의 상품 enrich와 같은 방식.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;

    /** 공개 목록에 노출할 상태: 판매중·품절. 판매중지(DISCONTINUED)는 제외한다. */
    private static final List<ProductStatus> VISIBLE_STATUSES =
            List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    /**
     * 공개 상품 목록 조회 / 검색·필터 (페이지).
     * 정책(VISIBLE_STATUSES)은 서비스가 쥐고 쿼리 조립은 리포지토리에 위임.
     */
    public PageResponse<ProductResponse> getProducts(ProductSearchCondition condition, Pageable pageable) {
        return searchPage(VISIBLE_STATUSES, condition, pageable);
    }

    /**
     * <b>어드민(백오피스) 상품 목록</b> — 판매중지(DISCONTINUED)를 포함한 <b>전 상태</b>를 본다.
     *
     * <p>운영 콘솔이 공개 목록 API를 재사용하면 상품을 판매중지로 바꾸는 순간 어드민 화면에서도 사라져
     * <b>되돌릴 수 없는 데이터 잠금</b>이 생긴다 → 공개 뷰(가시 상태만)와 백오피스 뷰(전 상태)의 경계를 분리한다.
     *
     * @param status 특정 상태만 보고 싶을 때(선택). null이면 전 상태.
     */
    public PageResponse<ProductResponse> getProductsForAdmin(
            ProductStatus status, ProductSearchCondition condition, Pageable pageable) {
        List<ProductStatus> statuses = (status != null) ? List.of(status) : List.of(ProductStatus.values());
        return searchPage(statuses, condition, pageable);
    }

    /**
     * 상태 집합 + 검색 조건으로 페이지 조회 후 enrich.
     * 카테고리·브랜드 이름은 id를 모아 한 번에 조회해 채운다(N+1 회피 — Cart enrich와 동일 발상).
     */
    private PageResponse<ProductResponse> searchPage(
            List<ProductStatus> statuses, ProductSearchCondition condition, Pageable pageable) {
        Page<Product> page = productRepository.search(statuses, condition, pageable);

        Map<Long, String> categoryNames = categoryNameMap(page.getContent());
        Map<Long, String> brandNames = brandNameMap(page.getContent());

        // Map.get(null)은 null을 돌려주므로 categoryId/brandId가 없는 상품도 안전하다.
        return PageResponse.from(page.map(product -> ProductResponse.of(
                product, categoryNames.get(product.getCategoryId()), brandNames.get(product.getBrandId()))));
    }

    /** 상품 등록: 신규 상품은 ON_SALE. 카테고리/브랜드 id가 주어지면 존재를 검증(없으면 400). */
    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        validateRefExists(categoryRepository, request.categoryId(), "카테고리");
        validateRefExists(brandRepository, request.brandId(), "브랜드");

        Product product = Product.builder()
                .name(request.name())
                .price(request.price())
                .originalPrice(request.originalPrice())
                .description(request.description())
                .imageUrl(request.imageUrl())
                .status(ProductStatus.ON_SALE)
                .categoryId(request.categoryId())
                .brandId(request.brandId())
                .build();

        // 옵션(사이즈별 재고)을 애그리거트 루트에 추가 → cascade로 함께 저장
        request.options().forEach(opt ->
                product.addOption(ProductOption.create(opt.size(), opt.stock())));

        return enrich(productRepository.save(product));
    }

    /**
     * 커서 기반 피드(무한 스크롤) — {@code cursor} 미만 id의 노출 상품을 최신순(id desc)으로 size개.
     * size+1을 읽어 다음 페이지 존재를 판정하고, 마지막 id를 nextCursor로 돌려준다. offset 없이 인덱스 탐색.
     */
    public ProductCursorResponse feed(Long cursor, int size) {
        int limit = Math.min(Math.max(size, 1), 50);   // 1~50 클램프(남용 방지)
        List<Product> rows = productRepository.findFeed(VISIBLE_STATUSES, cursor, PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        List<Product> page = hasNext ? rows.subList(0, limit) : rows;

        Map<Long, String> categoryNames = categoryNameMap(page);
        Map<Long, String> brandNames = brandNameMap(page);
        List<ProductResponse> items = page.stream()
                .map(p -> ProductResponse.of(p, categoryNames.get(p.getCategoryId()), brandNames.get(p.getBrandId())))
                .toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new ProductCursorResponse(items, nextCursor, hasNext);
    }

    /** 단건 조회 — 읽기 빈도 최상이라 캐시(productDetail, key=상품 id). 쓰기/리뷰/찜 변동 시 evict로 무효화. */
    @Cacheable(value = CacheConfig.PRODUCT_DETAIL, key = "#id")
    public ProductResponse getProduct(Long id) {
        return enrich(findProduct(id));
    }

    /**
     * 인기 상품 ID 상위 12(ON_SALE·찜 수→리뷰 수). 추천 콜드스타트 폴백이 반복 호출하므로 캐시한다.
     * ID만 캐시(enrich는 호출부에서) → 이름 staleness 표면 최소. 5분 TTL로 카운터 드리프트를 자가 수렴(수동 무효화 X).
     */
    @Cacheable(value = CacheConfig.POPULAR_PRODUCTS)
    public List<Long> popularProductIds() {
        return productRepository.findTop12ByStatusOrderByWishlistCountDescRatingCountDesc(ProductStatus.ON_SALE)
                .stream().map(Product::getId).toList();
    }

    /** 기본정보 수정 (ADMIN). 없는 상품 404, 카테고리/브랜드 id가 있으면 존재 검증(없으면 400). 옵션·이미지·상태는 별도 API. */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#id")
    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = findProduct(id);
        validateRefExists(categoryRepository, request.categoryId(), "카테고리");
        validateRefExists(brandRepository, request.brandId(), "브랜드");
        product.updateBasics(request.name(), request.price(), request.originalPrice(), request.description(),
                request.imageUrl(), request.categoryId(), request.brandId());
        return enrich(product);
    }

    /**
     * 옵션(사이즈) 추가 (ADMIN). 같은 사이즈가 이미 있으면 409. 갱신된 상품을 반환한다.
     * 새 옵션의 id를 응답에 채우려면 cascade INSERT를 즉시 반영해야 하므로 saveAndFlush.
     */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#productId")
    @Transactional
    public ProductResponse addOption(Long productId, ProductOptionUpsertRequest request) {
        Product product = findProduct(productId);
        product.addOption(request.size(), request.stock());
        productRepository.saveAndFlush(product);
        return enrich(product);
    }

    /** 옵션 수정 (ADMIN). 없는 옵션 404, 다른 옵션과 사이즈 중복 409. 더티체킹으로 반영. */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#productId")
    @Transactional
    public ProductResponse updateOption(Long productId, Long optionId, ProductOptionUpsertRequest request) {
        Product product = findProduct(productId);
        product.updateOption(optionId, request.size(), request.stock());
        return enrich(product);
    }

    /** 옵션 삭제 (ADMIN). 없는 옵션 404. orphanRemoval로 행 삭제. */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#productId")
    @Transactional
    public ProductResponse removeOption(Long productId, Long optionId) {
        Product product = findProduct(productId);
        product.removeOption(optionId);
        return enrich(product);
    }

    /** 상품 상태 변경 (ADMIN). 없는 상품 404. 더티체킹으로 반영. */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#id")
    @Transactional
    public ProductResponse changeStatus(Long id, ProductStatusUpdateRequest request) {
        Product product = findProduct(id);
        product.changeStatus(request.status());
        return enrich(product);
    }

    /** 이미지(갤러리) 추가 (ADMIN). 새 이미지 id를 응답에 채우려 saveAndFlush. */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#productId")
    @Transactional
    public ProductResponse addImage(Long productId, ProductImageCreateRequest request) {
        Product product = findProduct(productId);
        product.addImage(request.url());
        productRepository.saveAndFlush(product);
        return enrich(product);
    }

    /** 이미지(갤러리) 삭제 (ADMIN). 없는 이미지 404. orphanRemoval로 행 삭제. */
    @CacheEvict(value = CacheConfig.PRODUCT_DETAIL, key = "#productId")
    @Transactional
    public ProductResponse removeImage(Long productId, Long imageId) {
        Product product = findProduct(productId);
        product.removeImage(imageId);
        return enrich(product);
    }

    private Product findProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."));
    }

    /**
     * 여러 상품을 ID로 한 번에 조회해 {id: 응답} 맵으로 반환(이름 enrich 포함). 찜 목록처럼 "상품 ID 묶음 →
     * 상품 정보"가 필요한 다른 도메인이 N+1 없이 재사용한다(상품 enrich 로직은 여기 한 곳에만 둔다).
     * 호출자가 원래 순서(예: 찜한 최신순)대로 다시 배열할 수 있도록 List가 아니라 Map으로 돌려준다.
     * 존재하지 않는 ID는 맵에 없다(삭제된 상품 등 — 호출자가 null 처리).
     */
    public Map<Long, ProductResponse> getProductMap(Collection<Long> ids) {
        List<Product> products = productRepository.findAllById(ids);
        Map<Long, String> categoryNames = categoryNameMap(products);
        Map<Long, String> brandNames = brandNameMap(products);
        return products.stream().collect(Collectors.toMap(
                Product::getId,
                p -> ProductResponse.of(p, categoryNames.get(p.getCategoryId()), brandNames.get(p.getBrandId()))));
    }

    // --- enrich: 상품의 categoryId/brandId로 이름을 채워 응답 생성 ---

    /** 단건 enrich: 카테고리/브랜드를 각각 조회해 이름을 채운다(없으면 null). */
    private ProductResponse enrich(Product product) {
        String categoryName = product.getCategoryId() == null ? null
                : categoryRepository.findById(product.getCategoryId()).map(Category::getName).orElse(null);
        String brandName = product.getBrandId() == null ? null
                : brandRepository.findById(product.getBrandId()).map(Brand::getName).orElse(null);
        return ProductResponse.of(product, categoryName, brandName);
    }

    /** 목록용: 상품들의 categoryId를 모아 한 번에 조회 → {id: name} 맵. */
    private Map<Long, String> categoryNameMap(List<Product> products) {
        Set<Long> ids = products.stream().map(Product::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return categoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    /** 목록용: 상품들의 brandId를 모아 한 번에 조회 → {id: name} 맵. */
    private Map<Long, String> brandNameMap(List<Product> products) {
        Set<Long> ids = products.stream().map(Product::getBrandId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return brandRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Brand::getId, Brand::getName));
    }

    /** 참조 id가 주어졌는데 해당 엔티티가 없으면 400. (Category/Brand 리포지토리 공용) */
    private void validateRefExists(CrudRepository<?, Long> repository, Long id, String label) {
        if (id != null && !repository.existsById(id)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "존재하지 않는 " + label + "입니다. (id: " + id + ")");
        }
    }
}
