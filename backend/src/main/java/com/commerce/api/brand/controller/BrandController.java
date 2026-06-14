package com.commerce.api.brand.controller;

import com.commerce.api.brand.dto.BrandCreateRequest;
import com.commerce.api.brand.dto.BrandResponse;
import com.commerce.api.brand.dto.BrandSellerAssignRequest;
import com.commerce.api.brand.service.BrandService;
import com.commerce.api.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 API.
 * - GET  /api/brands              목록 조회 (공개)
 * - POST /api/brands              등록 (ADMIN)
 * - PUT  /api/brands/{id}/seller  셀러 귀속 (ADMIN)
 */
@Tag(name = "브랜드(Brand)", description = "브랜드 조회/등록 API")
@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @Operation(summary = "브랜드 목록 조회", description = "전체 브랜드를 조회한다. (공개)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<BrandResponse>>> getBrands() {
        return ResponseEntity.ok(ApiResponse.success(brandService.getBrands()));
    }

    @Operation(summary = "브랜드 등록(ADMIN)", description = "브랜드를 등록한다. 이름 중복이면 409.")
    @PostMapping
    public ResponseEntity<ApiResponse<BrandResponse>> create(
            @Valid @RequestBody BrandCreateRequest request) {
        BrandResponse response = brandService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("브랜드가 등록되었습니다.", response));
    }

    @Operation(summary = "브랜드 셀러 귀속(ADMIN)",
            description = "브랜드를 셀러에 귀속한다. sellerId가 null이면 귀속 해제. 브랜드 없으면 404, 셀러 없으면 400.")
    @PutMapping("/{id}/seller")
    public ResponseEntity<ApiResponse<BrandResponse>> assignSeller(
            @PathVariable Long id, @RequestBody BrandSellerAssignRequest request) {
        BrandResponse response = brandService.assignSeller(id, request.sellerId());
        return ResponseEntity.ok(ApiResponse.success("브랜드 셀러 귀속이 변경되었습니다.", response));
    }
}
