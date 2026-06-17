package com.commerce.api.brand.repository;

import com.commerce.api.brand.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 브랜드 DB 접근.
 */
public interface BrandRepository extends JpaRepository<Brand, Long> {

    /** 이름 중복 등록 방지용. */
    boolean existsByName(String name);

    /** 수정 시 이름 중복 검사 — 자기 자신(id)은 제외하고 같은 이름이 있는지. (이름 그대로 두면 false) */
    boolean existsByNameAndIdNot(String name, Long id);
}
