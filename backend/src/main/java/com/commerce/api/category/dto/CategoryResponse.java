package com.commerce.api.category.dto;

import com.commerce.api.category.entity.Category;

/**
 * 카테고리 응답. parentId=null이면 최상위, 값이면 그 부모의 자식(FE가 2단계로 그룹핑).
 */
public record CategoryResponse(Long id, String name, Long parentId) {

    /** parentId 없는 호출용 편의 생성자(최상위). 기존 호출부 호환. */
    public CategoryResponse(Long id, String name) {
        this(id, name, null);
    }

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getParentId());
    }
}
