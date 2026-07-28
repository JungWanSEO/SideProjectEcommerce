package com.commerce.api.seller.repository;

import com.commerce.api.seller.entity.Seller;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 셀러 DB 접근.
 */
public interface SellerRepository extends JpaRepository<Seller, Long> {

    /** 이름 중복 등록 방지용. */
    boolean existsByName(String name);

    /** 이름으로 조회(name은 UNIQUE) — 데모 시드가 "없으면 생성"(멱등)할 때 쓴다. */
    Optional<Seller> findByName(String name);
}
