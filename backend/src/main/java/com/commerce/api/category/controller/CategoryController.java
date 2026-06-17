package com.commerce.api.category.controller;

import com.commerce.api.category.dto.CategoryCreateRequest;
import com.commerce.api.category.dto.CategoryResponse;
import com.commerce.api.category.dto.CategoryUpdateRequest;
import com.commerce.api.category.service.CategoryService;
import com.commerce.api.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카테고리 API.
 * - GET    /api/categories       목록 조회 (공개)
 * - POST   /api/categories       등록 (ADMIN)
 * - PUT    /api/categories/{id}  수정 (ADMIN)
 * - DELETE /api/categories/{id}  삭제 (ADMIN)
 */
@Tag(name = "카테고리(Category)", description = "상품 카테고리 조회/등록 API")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "카테고리 목록 조회", description = "전체 카테고리를 조회한다. (공개)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.success(categoryService.getCategories()));
    }

    @Operation(summary = "카테고리 등록(ADMIN)", description = "카테고리를 등록한다. 이름 중복이면 409.")
    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> create(
            @Valid @RequestBody CategoryCreateRequest request) {
        CategoryResponse response = categoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("카테고리가 등록되었습니다.", response));
    }

    @Operation(summary = "카테고리 수정(ADMIN)",
            description = "이름·부모를 수정한다. 없으면 404, 이름 중복이면 409, 2단계 제약 위반이면 400.")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> update(
            @PathVariable Long id, @Valid @RequestBody CategoryUpdateRequest request) {
        CategoryResponse response = categoryService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success("카테고리가 수정되었습니다.", response));
    }

    @Operation(summary = "카테고리 삭제(ADMIN)",
            description = "카테고리를 삭제한다. 없으면 404, 자식 카테고리가 있거나 상품이 참조 중이면 409.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("카테고리가 삭제되었습니다.", null));
    }
}
