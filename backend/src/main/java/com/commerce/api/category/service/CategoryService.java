package com.commerce.api.category.service;

import com.commerce.api.category.dto.CategoryCreateRequest;
import com.commerce.api.category.dto.CategoryResponse;
import com.commerce.api.category.entity.Category;
import com.commerce.api.category.repository.CategoryRepository;
import com.commerce.api.global.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카테고리 비즈니스 로직 — 목록 조회 / 등록.
 */
@Service
@RequiredArgsConstructor          // private final 필드를 받는 생성자 자동 생성(생성자 주입)
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    /** 전체 카테고리 목록(평면, parentId 포함). FE가 parentId로 2단계(부모→자식) 그룹핑한다. */
    public List<CategoryResponse> getCategories() {
        return categoryRepository.findAll().stream().map(CategoryResponse::from).toList();
    }

    /**
     * 카테고리 등록(ADMIN). 이름 중복이면 409. parentId를 주면 자식으로 등록하되 <b>2단계까지만</b>:
     * 부모가 없으면 400, 부모가 이미 자식(parentId != null)이면 400(3단계 금지).
     */
    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        if (categoryRepository.existsByName(request.name())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 카테고리입니다.");
        }
        if (request.parentId() != null) {
            Category parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.BAD_REQUEST, "존재하지 않는 상위 카테고리입니다. (id: " + request.parentId() + ")"));
            if (parent.getParentId() != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "카테고리는 2단계(부모→자식)까지만 가능합니다.");
            }
        }
        return CategoryResponse.from(
                categoryRepository.save(Category.create(request.name(), request.parentId())));
    }
}
