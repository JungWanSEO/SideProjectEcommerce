package com.commerce.api.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.category.repository.CategoryRepository;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.dto.ProductCreateRequest;
import com.commerce.api.product.dto.ProductCursorResponse;
import com.commerce.api.product.dto.ProductImageCreateRequest;
import com.commerce.api.product.dto.ProductOptionUpsertRequest;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.dto.ProductStatusUpdateRequest;
import com.commerce.api.product.dto.ProductUpdateRequest;
import com.commerce.api.product.dto.ProductCreateRequest.ProductOptionRequest;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductImage;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ProductService 단위 테스트 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;   // enrich/검증에서 사용 (이름 조회·존재검증)
    @Mock
    private BrandRepository brandRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    @DisplayName("feed - size+1을 읽어 hasNext 판정, items는 size로 트림, nextCursor=마지막 id")
    void feed_cursorPaging() {
        // size=2 인데 findFeed가 3개(size+1) 반환 → 다음 페이지 있음
        given(productRepository.findFeed(any(), any(), any()))
                .willReturn(List.of(productWithId(3L), productWithId(2L), productWithId(1L)));

        ProductCursorResponse resp = productService.feed(null, 2);

        assertThat(resp.hasNext()).isTrue();
        assertThat(resp.items()).extracting(ProductResponse::id).containsExactly(3L, 2L);   // 트림
        assertThat(resp.nextCursor()).isEqualTo(2L);   // 마지막 항목 id
    }

    private Product productWithId(Long id) {
        Product product = Product.builder()
                .name("반팔티셔츠")
                .price(29000L)
                .description("면 100%")
                .status(ProductStatus.ON_SALE)
                .build();
        ReflectionTestUtils.setField(product, "id", id);
        product.addOption(ProductOption.create("M", 100));   // 재고는 옵션에
        return product;
    }

    @Test
    @DisplayName("상품 등록 성공 - 신규 상품은 ON_SALE 상태로 저장된다")
    void create_success() {
        // given
        ProductCreateRequest request = new ProductCreateRequest(
                "반팔티셔츠", 29000L, null, "면 100%", null, null, null,
                List.of(new ProductOptionRequest("M", 100)));
        given(productRepository.save(any(Product.class))).willReturn(productWithId(1L));

        // when
        ProductResponse response = productService.create(request);

        // then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.price()).isEqualTo(29000L);
        assertThat(response.status()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(response.options()).hasSize(1);
        assertThat(response.options().get(0).size()).isEqualTo("M");
        assertThat(response.options().get(0).stock()).isEqualTo(100);
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("상품 등록 - 정가(originalPrice)가 판매가 이상이면 응답에 실린다")
    void create_withOriginalPrice() {
        ProductCreateRequest request = new ProductCreateRequest(
                "세일상품", 8000L, 10000L, "desc", null, null, null,   // 판매가 8000·정가 10000(20% off)
                List.of(new ProductOptionRequest("M", 10)));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.create(request);

        assertThat(response.price()).isEqualTo(8000L);
        assertThat(response.originalPrice()).isEqualTo(10000L);   // 정가가 응답에 노출(할인율은 FE 계산)
    }

    @Test
    @DisplayName("상품 등록 실패 - 정가가 판매가보다 작으면 400 (음수 할인 방지)")
    void create_originalPriceBelowPrice_400() {
        ProductCreateRequest request = new ProductCreateRequest(
                "이상상품", 29000L, 20000L, "desc", null, null, null,   // 정가 20000 < 판매가 29000
                List.of(new ProductOptionRequest("M", 10)));

        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("상품 조회 성공")
    void getProduct_success() {
        // given
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithId(1L)));

        // when
        ProductResponse response = productService.getProduct(1L);

        // then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("반팔티셔츠");
    }

    @Test
    @DisplayName("상품 목록/검색 - 가시 상태 + 검색조건으로 search 호출해 PageResponse로 변환한다")
    void getProducts_success() {
        // given
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        ProductSearchCondition condition = new ProductSearchCondition("티", 1000L, 5000L, null, null, null);
        Page<Product> page =
                new PageImpl<>(List.of(productWithId(1L), productWithId(2L)), pageable, 2);
        given(productRepository.search(any(), any(), any())).willReturn(page);

        // when
        PageResponse<ProductResponse> response = productService.getProducts(condition, pageable);

        // then - 페이지 메타가 그대로 옮겨지고 엔티티가 DTO로 매핑된다
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).id()).isEqualTo(1L);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();

        // 가시 상태(ON_SALE·SOLD_OUT) + 받은 조건/페이지를 그대로 리포지토리에 전달한다 (DISCONTINUED 제외)
        verify(productRepository).search(
                List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT), condition, pageable);
    }

    @Test
    @DisplayName("어드민 목록 - status 미지정이면 전 상태(판매중지 포함)로 조회한다 (공개 목록과 경계 분리)")
    @SuppressWarnings("unchecked")
    void getProductsForAdmin_nullStatus_includesDiscontinued() {
        Pageable pageable = PageRequest.of(0, 20);
        ProductSearchCondition condition = new ProductSearchCondition(null, null, null, null, null, null);
        given(productRepository.search(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        productService.getProductsForAdmin(null, condition, pageable);

        // 회귀 방어: 공개 뷰(ON_SALE·SOLD_OUT)로 좁히면 어드민이 DISCONTINUED를 못 봐 되돌릴 수 없다(데이터 잠금).
        ArgumentCaptor<List<ProductStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(productRepository).search(statuses.capture(), eq(condition), eq(pageable));
        assertThat(statuses.getValue())
                .contains(ProductStatus.DISCONTINUED)
                .containsExactlyInAnyOrder(ProductStatus.values());
    }

    @Test
    @DisplayName("어드민 목록 - status 지정이면 그 상태만 조회한다")
    void getProductsForAdmin_withStatus_filtersOne() {
        Pageable pageable = PageRequest.of(0, 20);
        ProductSearchCondition condition = new ProductSearchCondition(null, null, null, null, null, null);
        given(productRepository.search(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        productService.getProductsForAdmin(ProductStatus.DISCONTINUED, condition, pageable);

        verify(productRepository).search(List.of(ProductStatus.DISCONTINUED), condition, pageable);
    }

    @Test
    @DisplayName("상품 조회 실패 - 없는 상품이면 예외")
    void getProduct_notFound() {
        // given
        given(productRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getProduct(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("상품을 찾을 수 없습니다");
    }

    /** id가 부여된 옵션 1개를 가진 상품. */
    private Product productWithOption(Long productId, Long optionId, String size, int stock) {
        Product product = Product.builder()
                .name("반팔티셔츠").price(29000L).status(ProductStatus.ON_SALE).build();
        ReflectionTestUtils.setField(product, "id", productId);
        ProductOption option = ProductOption.create(size, stock);
        ReflectionTestUtils.setField(option, "id", optionId);
        product.addOption(option);
        return product;
    }

    @Test
    @DisplayName("옵션 추가 성공 - 새 사이즈가 옵션 목록에 더해진다")
    void addOption_success() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithOption(1L, 10L, "M", 100)));

        ProductResponse response = productService.addOption(1L, new ProductOptionUpsertRequest("L", 50));

        assertThat(response.options()).extracting(o -> o.size()).contains("M", "L");
        assertThat(response.options()).hasSize(2);
        verify(productRepository).saveAndFlush(any(Product.class));
    }

    @Test
    @DisplayName("옵션 추가 실패 - 같은 사이즈가 이미 있으면 409")
    void addOption_duplicateSize() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithOption(1L, 10L, "M", 100)));

        assertThatThrownBy(() -> productService.addOption(1L, new ProductOptionUpsertRequest("M", 50)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 존재하는 사이즈");
    }

    @Test
    @DisplayName("옵션 수정 성공 - 사이즈/재고가 갱신된다")
    void updateOption_success() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithOption(1L, 10L, "M", 100)));

        ProductResponse response = productService.updateOption(1L, 10L, new ProductOptionUpsertRequest("L", 50));

        assertThat(response.options().get(0).size()).isEqualTo("L");
        assertThat(response.options().get(0).stock()).isEqualTo(50);
    }

    @Test
    @DisplayName("옵션 수정 실패 - 없는 옵션이면 404")
    void updateOption_notFound() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithOption(1L, 10L, "M", 100)));

        assertThatThrownBy(() -> productService.updateOption(1L, 999L, new ProductOptionUpsertRequest("L", 50)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("옵션을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("옵션 삭제 성공 - 옵션이 목록에서 제거된다")
    void removeOption_success() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithOption(1L, 10L, "M", 100)));

        ProductResponse response = productService.removeOption(1L, 10L);

        assertThat(response.options()).isEmpty();
    }

    @Test
    @DisplayName("옵션 삭제 실패 - 없는 옵션이면 404")
    void removeOption_notFound() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithOption(1L, 10L, "M", 100)));

        assertThatThrownBy(() -> productService.removeOption(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("옵션을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("상품 상태 변경 성공 - status가 바뀐다")
    void changeStatus_success() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithId(1L)));

        ProductResponse response =
                productService.changeStatus(1L, new ProductStatusUpdateRequest(ProductStatus.DISCONTINUED));

        assertThat(response.status()).isEqualTo(ProductStatus.DISCONTINUED);
    }

    @Test
    @DisplayName("상품 상태 변경 실패 - 없는 상품이면 404")
    void changeStatus_notFound() {
        given(productRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                productService.changeStatus(999L, new ProductStatusUpdateRequest(ProductStatus.SOLD_OUT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("상품을 찾을 수 없습니다");
    }

    /** id가 부여된 이미지 1장을 가진 상품. */
    private Product productWithImage(Long productId, Long imageId, String url) {
        Product product = Product.builder()
                .name("반팔티셔츠").price(29000L).status(ProductStatus.ON_SALE).build();
        ReflectionTestUtils.setField(product, "id", productId);
        ProductImage image = product.addImage(url);
        ReflectionTestUtils.setField(image, "id", imageId);
        return product;
    }

    @Test
    @DisplayName("이미지 추가 성공 - 갤러리에 더해진다")
    void addImage_success() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithId(1L)));

        ProductResponse response = productService.addImage(1L, new ProductImageCreateRequest("/products/2.svg"));

        assertThat(response.images()).hasSize(1);
        assertThat(response.images().get(0).url()).isEqualTo("/products/2.svg");
        verify(productRepository).saveAndFlush(any(Product.class));
    }

    @Test
    @DisplayName("이미지 삭제 성공 - 갤러리에서 제거된다")
    void removeImage_success() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithImage(1L, 50L, "/products/2.svg")));

        ProductResponse response = productService.removeImage(1L, 50L);

        assertThat(response.images()).isEmpty();
    }

    @Test
    @DisplayName("이미지 삭제 실패 - 없는 이미지면 404")
    void removeImage_notFound() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithImage(1L, 50L, "/products/2.svg")));

        assertThatThrownBy(() -> productService.removeImage(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미지를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("상품 수정 성공 - 기본정보가 갱신된다")
    void update_success() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithId(1L)));

        ProductResponse response = productService.update(1L,
                new ProductUpdateRequest("새이름", 50000L, null, "새설명", "/products/9.svg", null, null));

        assertThat(response.name()).isEqualTo("새이름");
        assertThat(response.price()).isEqualTo(50000L);
        assertThat(response.imageUrl()).isEqualTo("/products/9.svg");
    }

    @Test
    @DisplayName("상품 수정 실패 - 정가가 판매가보다 작으면 400 (수정 경로도 가드)")
    void update_originalPriceBelowPrice_400() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithId(1L)));

        assertThatThrownBy(() -> productService.update(1L,
                new ProductUpdateRequest("n", 50000L, 40000L, null, null, null, null)))   // 정가 40000 < 판매가 50000
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("상품 수정 실패 - 없는 상품이면 404")
    void update_notFound() {
        given(productRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productService.update(999L,
                new ProductUpdateRequest("n", 1L, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("상품을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("상품 수정 실패 - 존재하지 않는 카테고리면 400")
    void update_invalidCategory() {
        given(productRepository.findById(1L)).willReturn(Optional.of(productWithId(1L)));
        given(categoryRepository.existsById(99L)).willReturn(false);

        assertThatThrownBy(() -> productService.update(1L,
                new ProductUpdateRequest("n", 1L, null, null, null, 99L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("존재하지 않는 카테고리");
    }
}