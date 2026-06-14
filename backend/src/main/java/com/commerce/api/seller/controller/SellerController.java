package com.commerce.api.seller.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.seller.dto.SellerCreateRequest;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.dto.SellerUpdateRequest;
import com.commerce.api.seller.service.SellerService;
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
 * 셀러(입점사) API — 전부 ADMIN 운영 업무(SecurityConfig: /api/sellers/** hasRole ADMIN).
 * - GET    /api/sellers           목록
 * - GET    /api/sellers/{id}      단건
 * - POST   /api/sellers           등록
 * - PUT    /api/sellers/{id}      기본 정보 수정
 * - PUT    /api/sellers/{id}/suspend   입점 정지
 * - PUT    /api/sellers/{id}/activate  입점 재개
 */
@Tag(name = "셀러(Seller)", description = "입점 셀러 관리 API (ADMIN)")
@RestController
@RequestMapping("/api/sellers")
@RequiredArgsConstructor
public class SellerController {

    private final SellerService sellerService;

    @Operation(summary = "셀러 목록 조회(ADMIN)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<SellerResponse>>> getSellers() {
        return ResponseEntity.ok(ApiResponse.success(sellerService.getSellers()));
    }

    @Operation(summary = "셀러 단건 조회(ADMIN)", description = "없으면 404.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SellerResponse>> getSeller(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(sellerService.getSeller(id)));
    }

    @Operation(summary = "셀러 등록(ADMIN)", description = "이름 중복이면 409.")
    @PostMapping
    public ResponseEntity<ApiResponse<SellerResponse>> create(
            @Valid @RequestBody SellerCreateRequest request) {
        SellerResponse response = sellerService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("셀러가 등록되었습니다.", response));
    }

    @Operation(summary = "셀러 정보 수정(ADMIN)", description = "이름·수수료율·정산계좌·사업자번호. 상태는 별도 엔드포인트.")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SellerResponse>> update(
            @PathVariable Long id, @Valid @RequestBody SellerUpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("셀러 정보가 수정되었습니다.", sellerService.update(id, request)));
    }

    @Operation(summary = "셀러 입점 정지(ADMIN)", description = "이미 정지면 409.")
    @PutMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<SellerResponse>> suspend(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.success("셀러가 정지되었습니다.", sellerService.suspend(id)));
    }

    @Operation(summary = "셀러 입점 재개(ADMIN)", description = "이미 활성이면 409.")
    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<SellerResponse>> activate(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.success("셀러가 활성화되었습니다.", sellerService.activate(id)));
    }
}
