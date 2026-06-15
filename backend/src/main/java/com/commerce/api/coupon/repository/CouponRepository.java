package com.commerce.api.coupon.repository;

import com.commerce.api.coupon.entity.Coupon;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 쿠폰 저장소. 코드는 대문자 정규화해 저장하므로 조회 시에도 정규화한 값으로 찾는다(CouponService).
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);
}
