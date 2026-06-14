package com.commerce.api.seller.repository;

import com.commerce.api.seller.entity.Seller;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 셀러 DB 접근.
 */
public interface SellerRepository extends JpaRepository<Seller, Long> {

    /** 이름 중복 등록 방지용. */
    boolean existsByName(String name);
}
