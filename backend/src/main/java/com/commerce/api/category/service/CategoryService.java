package com.commerce.api.category.service;

import com.commerce.api.category.dto.CategoryCreateRequest;
import com.commerce.api.category.dto.CategoryResponse;
import com.commerce.api.category.dto.CategoryUpdateRequest;
import com.commerce.api.category.entity.Category;
import com.commerce.api.category.repository.CategoryRepository;
import com.commerce.api.global.config.CacheConfig;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카테고리 비즈니스 로직 — 목록 조회 / 등록 / 수정 / 삭제.
 */
@Service
@RequiredArgsConstructor          // private final 필드를 받는 생성자 자동 생성(생성자 주입)
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    // 삭제 시 "이 카테고리를 쓰는 상품이 있는가" 참조 무결성 검증용(별도 애그리거트 → ID 참조 검증).
    // BrandService가 SellerRepository를 참조검증에 쓰는 것과 같은 패턴.
    private final ProductRepository productRepository;

    /** 전체 카테고리 목록(평면, parentId 포함). 거의 안 바뀌어 캐시 — 변경(추가/수정/삭제) 시에만 무효화. */
    @Cacheable(value = CacheConfig.CATEGORY_LIST)
    public List<CategoryResponse> getCategories() {
        return categoryRepository.findAll().stream().map(CategoryResponse::from).toList();
    }

    /**
     * 카테고리 등록(ADMIN). 이름 중복이면 409. parentId를 주면 자식으로 등록하되 <b>2단계까지만</b>:
     * 부모가 없으면 400, 부모가 이미 자식(parentId != null)이면 400(3단계 금지).
     */
    @CacheEvict(value = CacheConfig.CATEGORY_LIST, allEntries = true)
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

    /**
     * 카테고리 수정(ADMIN) — 이름·부모를 갱신한다. 없으면 404.
     * <ul>
     *   <li>이름이 다른 카테고리와 겹치면 409(자기 자신은 제외 — 이름 그대로 둬도 통과).</li>
     *   <li>parentId를 주면 2단계 제약을 지켜야 한다: 자기 자신(400)·없는 부모(400)·이미 자식인 부모(3단계, 400) 금지,
     *       그리고 <b>자식을 가진 카테고리는 하위로 옮길 수 없다</b>(옮기면 3단계가 되므로 400).</li>
     * </ul>
     */
    @CacheEvict(value = CacheConfig.CATEGORY_LIST, allEntries = true)
    @Transactional
    public CategoryResponse update(Long id, CategoryUpdateRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다."));
        if (categoryRepository.existsByNameAndIdNot(request.name(), id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 카테고리입니다.");
        }
        Long parentId = request.parentId();
        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "자기 자신을 상위 카테고리로 지정할 수 없습니다.");
            }
            Category parent = categoryRepository.findById(parentId)
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.BAD_REQUEST, "존재하지 않는 상위 카테고리입니다. (id: " + parentId + ")"));
            if (parent.getParentId() != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "카테고리는 2단계(부모→자식)까지만 가능합니다.");
            }
            if (categoryRepository.existsByParentId(id)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "자식 카테고리가 있어 하위로 옮길 수 없습니다. (2단계 초과)");
            }
        }
        category.update(request.name(), parentId);   // 영속 엔티티 → dirty checking flush
        return CategoryResponse.from(category);
    }

    /**
     * 카테고리 삭제(ADMIN). 없으면 404. <b>캐스케이드/소프트삭제 없음</b> —
     * 자식 카테고리가 있거나 상품이 참조 중이면 409로 막는다(데이터 정합 우선).
     */
    @CacheEvict(value = CacheConfig.CATEGORY_LIST, allEntries = true)
    @Transactional
    public void delete(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다."));
        if (categoryRepository.existsByParentId(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "자식 카테고리가 있어 삭제할 수 없습니다.");
        }
        if (productRepository.existsByCategoryId(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이 카테고리를 사용하는 상품이 있어 삭제할 수 없습니다.");
        }
        categoryRepository.delete(category);
    }
}
