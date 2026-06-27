package com.commerce.api.coupon.repository;

import com.commerce.api.coupon.entity.Coupon;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 쿠폰 저장소. 코드는 대문자 정규화해 저장하므로 조회 시에도 정규화한 값으로 찾는다(CouponService).
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * 선착순 발급 카운터를 <b>원자적·조건부</b>로 +1 한다 — 한도 내(무제한이거나 issuedCount &lt; totalQuantity)일 때만.
     * 반환값(영향 행 수): <b>1=발급 성공</b>, <b>0=마감(소진)</b>.
     *
     * <p>이게 동시성 핵심이다: 애플리케이션 락 없이 DB가 행 단위로 직렬화하므로(원자적 UPDATE),
     * 여러 스레드가 동시에 받아도 초과 발급이 구조적으로 불가능하다(lost update 없음).
     * 회원당 1장 제약은 member_coupon UNIQUE가, 둘의 정합은 같은 트랜잭션 롤백이 보장한다.
     */
    @Modifying
    @Query("update Coupon c set c.issuedCount = c.issuedCount + 1 "
            + "where c.id = :id and (c.totalQuantity is null or c.issuedCount < c.totalQuantity)")
    int incrementIssuedCount(@Param("id") Long id);
}
