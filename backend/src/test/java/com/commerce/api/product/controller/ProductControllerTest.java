package com.commerce.api.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.global.ratelimit.RateLimiter;
import com.commerce.api.product.dto.ProductImageResponse;
import com.commerce.api.product.dto.ProductOptionResponse;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.service.ProductService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ProductController 통합 테스트 (@WebMvcTest + MockMvc).
 * 보안 필터는 비활성(addFilters = false). 권한 검증은 SecurityConfig 통합 테스트 영역.
 */
@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private RateLimiter rateLimiter; // @WebMvcTest 슬라이스엔 @Component가 안 올라오므로 목 주입(check=no-op)

    @Test
    @DisplayName("POST /api/products - 등록 성공 시 201")
    void create_success() throws Exception {
        given(productService.create(any())).willReturn(
                new ProductResponse(1L, "반팔티셔츠", 29000L, "면 100%", "/products/1.svg",
                        ProductStatus.ON_SALE, 1L, "상의", 1L, "Nike",
                        List.of(new ProductOptionResponse(10L, "M", 100, false)), 0, 0.0, 0, LocalDateTime.now()));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"반팔티셔츠","price":29000,"description":"면 100%","options":[{"size":"M","stock":100}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.price").value(29000))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.categoryName").value("상의"))
                .andExpect(jsonPath("$.data.brandName").value("Nike"))
                .andExpect(jsonPath("$.data.imageUrl").value("/products/1.svg"))
                .andExpect(jsonPath("$.data.options[0].size").value("M"))
                .andExpect(jsonPath("$.data.options[0].stock").value(100));
    }

    @Test
    @DisplayName("POST /api/products - 상품명 누락·음수 가격이면 400")
    void create_validationFail() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":-100,"options":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/products - 목록 200, 페이지 메타 포함 + 파라미터 없으면 기본 정렬(createdAt desc, size 20)")
    void getProducts_success() throws Exception {
        PageResponse<ProductResponse> page = new PageResponse<>(
                List.of(new ProductResponse(1L, "반팔티셔츠", 29000L, "면 100%", "/products/1.svg",
                        ProductStatus.ON_SALE, 1L, "상의", 1L, "Nike",
                        List.of(new ProductOptionResponse(10L, "M", 100, false)), 0, 0.0, 0, LocalDateTime.now())),
                0, 20, 1L, 1, false);
        given(productService.getProducts(any(ProductSearchCondition.class), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        // 파라미터가 없으면 @PageableDefault가 적용된다 (컨트롤러가 받은 Pageable을 캡처해 확인)
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).getProducts(any(ProductSearchCondition.class), captor.capture());
        Pageable used = captor.getValue();
        assertThat(used.getPageSize()).isEqualTo(20);
        Sort.Order order = used.getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /api/products - keyword·minPrice·maxPrice 쿼리 파라미터가 검색 조건으로 바인딩된다")
    void getProducts_withSearchParams() throws Exception {
        PageResponse<ProductResponse> empty = new PageResponse<>(List.of(), 0, 20, 0L, 0, false);
        given(productService.getProducts(any(ProductSearchCondition.class), any(Pageable.class)))
                .willReturn(empty);

        mockMvc.perform(get("/api/products")
                        .param("keyword", "셔츠")
                        .param("minPrice", "10000")
                        .param("maxPrice", "50000")
                        .param("categoryId", "3")
                        .param("brandId", "7"))
                .andExpect(status().isOk());

        // 컨트롤러가 받은 ProductSearchCondition을 캡처해 쿼리 파라미터가 제대로 바인딩됐는지 확인
        ArgumentCaptor<ProductSearchCondition> captor =
                ArgumentCaptor.forClass(ProductSearchCondition.class);
        verify(productService).getProducts(captor.capture(), any(Pageable.class));
        ProductSearchCondition cond = captor.getValue();
        assertThat(cond.keyword()).isEqualTo("셔츠");
        assertThat(cond.minPrice()).isEqualTo(10000L);
        assertThat(cond.maxPrice()).isEqualTo(50000L);
        assertThat(cond.categoryId()).isEqualTo(3L);
        assertThat(cond.brandId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("GET /api/products/{id} - 조회 성공 시 200")
    void getProduct_success() throws Exception {
        given(productService.getProduct(1L)).willReturn(
                new ProductResponse(1L, "반팔티셔츠", 29000L, "면 100%", "/products/1.svg",
                        ProductStatus.ON_SALE, 1L, "상의", 1L, "Nike",
                        List.of(new ProductOptionResponse(10L, "M", 100, false)), 0, 0.0, 0, LocalDateTime.now()));

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.options[0].size").value("M"))
                .andExpect(jsonPath("$.data.options[0].stock").value(100));
    }

    @Test
    @DisplayName("GET /api/products/{id} - 없는 상품이면 404")
    void getProduct_notFound() throws Exception {
        given(productService.getProduct(eq(999L)))
                .willThrow(new BusinessException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/products/{id} - id 자리에 숫자가 아닌 값이면 400 (500 아님)")
    void getProduct_typeMismatch() throws Exception {
        mockMvc.perform(get("/api/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private ProductResponse productWithOptions(ProductOptionResponse... options) {
        return new ProductResponse(1L, "반팔티셔츠", 29000L, "면 100%", "/products/1.svg",
                ProductStatus.ON_SALE, 1L, "상의", 1L, "Nike",
                List.of(options), 0, 0.0, 0, LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/products/{id}/options - 옵션 추가 성공 시 201")
    void addOption_success() throws Exception {
        given(productService.addOption(eq(1L), any())).willReturn(productWithOptions(
                new ProductOptionResponse(10L, "M", 100, false),
                new ProductOptionResponse(11L, "L", 50, false)));

        mockMvc.perform(post("/api/products/1/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"size":"L","stock":50}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.options[1].size").value("L"))
                .andExpect(jsonPath("$.data.options[1].stock").value(50));
    }

    @Test
    @DisplayName("POST /api/products/{id}/options - 사이즈 누락이면 400")
    void addOption_validationFail() throws Exception {
        mockMvc.perform(post("/api/products/1/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"size":"","stock":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PUT /api/products/{id}/options/{optionId} - 옵션 수정 성공 시 200")
    void updateOption_success() throws Exception {
        given(productService.updateOption(eq(1L), eq(10L), any())).willReturn(productWithOptions(
                new ProductOptionResponse(10L, "L", 50, false)));

        mockMvc.perform(put("/api/products/1/options/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"size":"L","stock":50}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options[0].size").value("L"))
                .andExpect(jsonPath("$.data.options[0].stock").value(50));
    }

    @Test
    @DisplayName("DELETE /api/products/{id}/options/{optionId} - 옵션 삭제 성공 시 200")
    void removeOption_success() throws Exception {
        given(productService.removeOption(eq(1L), eq(10L))).willReturn(productWithOptions());

        mockMvc.perform(delete("/api/products/1/options/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.options").isEmpty());
    }

    @Test
    @DisplayName("PATCH /api/products/{id}/status - 상태 변경 성공 시 200")
    void changeStatus_success() throws Exception {
        given(productService.changeStatus(eq(1L), any())).willReturn(
                new ProductResponse(1L, "반팔티셔츠", 29000L, "면 100%", "/products/1.svg",
                        ProductStatus.DISCONTINUED, 1L, "상의", 1L, "Nike",
                        List.of(), 0, 0.0, 0, LocalDateTime.now()));

        mockMvc.perform(patch("/api/products/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISCONTINUED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCONTINUED"));
    }

    @Test
    @DisplayName("PATCH /api/products/{id}/status - status 누락이면 400")
    void changeStatus_validationFail() throws Exception {
        mockMvc.perform(patch("/api/products/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PUT /api/products/{id} - 기본정보 수정 성공 시 200")
    void update_success() throws Exception {
        given(productService.update(eq(1L), any())).willReturn(
                new ProductResponse(1L, "새이름", 50000L, "새설명", "/products/9.svg",
                        ProductStatus.ON_SALE, 1L, "상의", 1L, "Nike",
                        List.of(), 0, 0.0, 0, LocalDateTime.now()));

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"새이름","price":50000,"description":"새설명","imageUrl":"/products/9.svg"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새이름"))
                .andExpect(jsonPath("$.data.price").value(50000));
    }

    @Test
    @DisplayName("PUT /api/products/{id} - 상품명 누락·음수 가격이면 400")
    void update_validationFail() throws Exception {
        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/products/{id}/images - 이미지 추가 성공 시 201")
    void addImage_success() throws Exception {
        given(productService.addImage(eq(1L), any())).willReturn(
                new ProductResponse(1L, "반팔티셔츠", 29000L, "면 100%", "/products/1.svg",
                        ProductStatus.ON_SALE, 1L, "상의", 1L, "Nike",
                        List.of(), 0, 0.0, 0, LocalDateTime.now(),
                        List.of(new ProductImageResponse(50L, "/products/2.svg", 0))));

        mockMvc.perform(post("/api/products/1/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"/products/2.svg"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.images[0].url").value("/products/2.svg"));
    }

    @Test
    @DisplayName("DELETE /api/products/{id}/images/{imageId} - 이미지 삭제 성공 시 200")
    void removeImage_success() throws Exception {
        given(productService.removeImage(eq(1L), eq(50L))).willReturn(productWithOptions());

        mockMvc.perform(delete("/api/products/1/images/50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.images").isEmpty());
    }

    @Test
    @DisplayName("GET /api/products/feed - IP 기준 레이트리밋을 확인한다(feed: 키·60/분)")
    void feed_callsRateLimiter() throws Exception {
        mockMvc.perform(get("/api/products/feed").param("size", "20"))
                .andExpect(status().isOk());

        verify(rateLimiter).check(startsWith("feed:"), eq(60)); // 스크래핑 억제: IP당 분당 상한
    }

    @Test
    @DisplayName("GET /api/products/feed - 레이트리밋 초과 시 429")
    void feed_tooManyRequests() throws Exception {
        willThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다."))
                .given(rateLimiter).check(any(), anyInt());

        mockMvc.perform(get("/api/products/feed"))
                .andExpect(status().isTooManyRequests());
    }
}