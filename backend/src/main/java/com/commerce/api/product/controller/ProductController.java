package com.commerce.api.product.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.product.dto.ProductCreateRequest;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 API.
 * - POST /api/products      상품 등록
 * - GET  /api/products      목록 조회 (페이지)
 * - GET  /api/products/{id} 단건 조회
 */
@Tag(name = "상품(Product)", description = "상품 등록 / 조회 API")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "상품 등록", description = "상품명/가격(원)/재고/설명으로 상품을 등록한다. 등록 시 상태는 ON_SALE.")
    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> create(
            @Valid @RequestBody ProductCreateRequest request) {
        ProductResponse response = productService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("상품이 등록되었습니다.", response));
    }

    @Operation(summary = "상품 목록 조회 / 검색·필터·정렬",
            description = "공개 상품 목록을 페이지로 조회한다. 판매중·품절만 노출(판매중지 제외). "
                    + "선택적 검색/필터: keyword(상품명 부분일치), minPrice·maxPrice(가격대), "
                    + "categoryId, brandId, optionSize(그 사이즈를 재고>0으로 가진 상품만). "
                    + "정렬(sort): createdAt(최신), price(가격), ratingCount(리뷰수), ratingAverage(평점평균), "
                    + "wishlistCount(인기순=찜수). "
                    + "기본 정렬은 최신순(createdAt desc), 기본 페이지 크기는 20. "
                    + "예: ?optionSize=M&minPrice=10000&sort=ratingAverage,desc")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> getProducts(
            // @ParameterObject: record의 필드(keyword/minPrice/maxPrice)를 각각의 쿼리 파라미터로 바인딩(+Swagger 문서화).
            @ParameterObject ProductSearchCondition condition,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        PageResponse<ProductResponse> response = productService.getProducts(condition, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "상품 단건 조회", description = "상품 ID로 상품 정보를 조회한다. 없으면 404.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable Long id) {
        ProductResponse response = productService.getProduct(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}