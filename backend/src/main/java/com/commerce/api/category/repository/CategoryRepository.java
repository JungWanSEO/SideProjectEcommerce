package com.commerce.api.category.repository;

import com.commerce.api.category.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 카테고리 DB 접근.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 이름 중복 등록 방지용. 메서드명 규칙으로 {@code exists ... where name = ?} 쿼리 생성. */
    boolean existsByName(String name);

    /** 수정 시 이름 중복 검사 — 자기 자신(id)은 제외하고 같은 이름이 있는지. (이름 그대로 두면 false) */
    boolean existsByNameAndIdNot(String name, Long id);

    /** 자식 카테고리 존재 여부 — 삭제 가드(자식 있으면 409)·재배치 가드(자식 있으면 하위로 못 옮김)에 사용. */
    boolean existsByParentId(Long parentId);
}
