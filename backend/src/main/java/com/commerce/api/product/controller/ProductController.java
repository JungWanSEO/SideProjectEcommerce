package com.commerce.api.product.controller;

import com.commerce.api.audit.aspect.Auditable;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.ratelimit.RateLimit;
import com.commerce.api.product.dto.ProductCreateRequest;
import com.commerce.api.product.dto.ProductCursorResponse;
import com.commerce.api.product.dto.ProductImageCreateRequest;
import com.commerce.api.product.dto.ProductOptionUpsertRequest;
import com.commerce.api.product.dto.ProductResponse;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.dto.ProductStatusUpdateRequest;
import com.commerce.api.product.dto.ProductUpdateRequest;
import com.commerce.api.product.entity.ProductStatus;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    // 공개 피드의 IP당 분당 상한 — 사람의 무한스크롤엔 넉넉(20개×60=1200개/분), 대량 스크래핑엔 제동.
    private static final int FEED_LIMIT_PER_MIN = 60;

    private final ProductService productService;

    @Operation(summary = "상품 등록", description = "상품명/가격(원)/재고/설명으로 상품을 등록한다. 등록 시 상태는 ON_SALE.")
    @Auditable(action = "PRODUCT_CREATE", targetType = "PRODUCT", targetId = "#result.body.data.id")
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

    @Operation(summary = "상품 목록 조회 (ADMIN · 백오피스)",
            description = "운영자용 상품 목록 — **판매중지(DISCONTINUED) 포함 전 상태**를 본다. "
                    + "공개 목록(GET /api/products)은 판매중·품절만 노출하므로, 그걸 재사용하면 판매중지로 바꾼 "
                    + "상품이 어드민에서도 사라져 되돌릴 수 없다(데이터 잠금). status로 특정 상태만 필터 가능(비우면 전체). "
                    + "검색/정렬 파라미터는 공개 목록과 동일.")
    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> getProductsForAdmin(
            @RequestParam(required = false) ProductStatus status,
            @ParameterObject ProductSearchCondition condition,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.success(productService.getProductsForAdmin(status, condition, pageable)));
    }

    @Operation(summary = "상품 피드 (커서 기반 무한 스크롤)",
            description = "노출 상품을 최신순(id desc)으로 cursor 미만부터 size개. 첫 페이지는 cursor 생략. "
                    + "응답의 nextCursor를 다음 요청 cursor로 넘긴다(hasNext=false면 끝). offset 없이 인덱스 탐색이라 깊은 페이지도 빠름. "
                    + "예: /api/products/feed?size=20 → /api/products/feed?cursor=<nextCursor>&size=20")
    // 스크래핑 억제: 커서 피드는 브라우저 무한스크롤 전용(SSR/sitemap은 offset API 사용)이라 IP 기준
    //   레이트리밋이 SSR을 안 건드리고 대량 수집만 제동한다. 초과 시 429 + Retry-After.
    //   (키 조립·IP 추출은 RateLimitAspect가 — app.ratelimit.enabled 로 토글)
    @RateLimit(key = "feed", limit = FEED_LIMIT_PER_MIN)
    @GetMapping("/feed")
    public ResponseEntity<ApiResponse<ProductCursorResponse>> feed(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(productService.feed(cursor, size)));
    }

    @Operation(summary = "상품 단건 조회", description = "상품 ID로 상품 정보를 조회한다. 없으면 404.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable Long id) {
        ProductResponse response = productService.getProduct(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "상품 기본정보 수정 (ADMIN)",
            description = "상품명/가격/설명/대표이미지/카테고리/브랜드를 수정한다. 옵션·이미지·상태는 별도 API. 없으면 404.")
    @Auditable(action = "PRODUCT_UPDATE", targetType = "PRODUCT", targetId = "#id")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductUpdateRequest request) {
        ProductResponse response = productService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success("상품이 수정되었습니다.", response));
    }

    @Operation(summary = "옵션 추가 (ADMIN)",
            description = "상품에 사이즈 옵션을 추가한다. 같은 사이즈가 이미 있으면 409. 갱신된 상품을 반환.")
    @Auditable(action = "PRODUCT_OPTION_ADD", targetType = "PRODUCT", targetId = "#productId")
    @PostMapping("/{productId}/options")
    public ResponseEntity<ApiResponse<ProductResponse>> addOption(
            @PathVariable Long productId,
            @Valid @RequestBody ProductOptionUpsertRequest request) {
        ProductResponse response = productService.addOption(productId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("옵션이 추가되었습니다.", response));
    }

    @Operation(summary = "옵션 수정 (ADMIN)",
            description = "옵션의 사이즈/재고를 수정한다. 없는 옵션이면 404, 다른 옵션과 사이즈가 겹치면 409.")
    @Auditable(action = "PRODUCT_OPTION_UPDATE", targetType = "PRODUCT", targetId = "#productId")
    @PutMapping("/{productId}/options/{optionId}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateOption(
            @PathVariable Long productId,
            @PathVariable Long optionId,
            @Valid @RequestBody ProductOptionUpsertRequest request) {
        ProductResponse response = productService.updateOption(productId, optionId, request);
        return ResponseEntity.ok(ApiResponse.success("옵션이 수정되었습니다.", response));
    }

    @Operation(summary = "옵션 삭제 (ADMIN)", description = "옵션을 삭제한다. 없는 옵션이면 404.")
    @Auditable(action = "PRODUCT_OPTION_REMOVE", targetType = "PRODUCT", targetId = "#productId")
    @DeleteMapping("/{productId}/options/{optionId}")
    public ResponseEntity<ApiResponse<ProductResponse>> removeOption(
            @PathVariable Long productId,
            @PathVariable Long optionId) {
        ProductResponse response = productService.removeOption(productId, optionId);
        return ResponseEntity.ok(ApiResponse.success("옵션이 삭제되었습니다.", response));
    }

    @Operation(summary = "상품 상태 변경 (ADMIN)",
            description = "상품 상태를 변경한다(ON_SALE/SOLD_OUT/DISCONTINUED). 없는 상품이면 404.")
    @Auditable(action = "PRODUCT_STATUS_CHANGE", targetType = "PRODUCT", targetId = "#id")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<ProductResponse>> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ProductStatusUpdateRequest request) {
        ProductResponse response = productService.changeStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success("상품 상태를 변경했습니다.", response));
    }

    @Operation(summary = "이미지(갤러리) 추가 (ADMIN)",
            description = "상품에 갤러리 이미지를 추가한다(대표 imageUrl 외 추가분). 갱신된 상품을 반환.")
    @Auditable(action = "PRODUCT_IMAGE_ADD", targetType = "PRODUCT", targetId = "#productId")
    @PostMapping("/{productId}/images")
    public ResponseEntity<ApiResponse<ProductResponse>> addImage(
            @PathVariable Long productId,
            @Valid @RequestBody ProductImageCreateRequest request) {
        ProductResponse response = productService.addImage(productId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("이미지가 추가되었습니다.", response));
    }

    @Operation(summary = "이미지(갤러리) 삭제 (ADMIN)", description = "갤러리 이미지를 삭제한다. 없는 이미지면 404.")
    @Auditable(action = "PRODUCT_IMAGE_REMOVE", targetType = "PRODUCT", targetId = "#productId")
    @DeleteMapping("/{productId}/images/{imageId}")
    public ResponseEntity<ApiResponse<ProductResponse>> removeImage(
            @PathVariable Long productId,
            @PathVariable Long imageId) {
        ProductResponse response = productService.removeImage(productId, imageId);
        return ResponseEntity.ok(ApiResponse.success("이미지가 삭제되었습니다.", response));
    }
}