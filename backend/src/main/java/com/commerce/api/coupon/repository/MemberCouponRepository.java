package com.commerce.api.coupon.repository;

import com.commerce.api.coupon.entity.MemberCoupon;
import com.commerce.api.coupon.entity.MemberCouponStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 쿠폰(지갑) 저장소.
 */
public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {

    /** 내 쿠폰함(최신 발급 먼저). */
    List<MemberCoupon> findByMemberIdOrderByIdDesc(Long memberId);

    /** 특정 회원이 보유한 특정 쿠폰 한 장(발급은 회원·쿠폰당 1장). 적용/복원에 쓴다. */
    Optional<MemberCoupon> findByMemberIdAndCouponId(Long memberId, Long couponId);

    /** 같은 쿠폰을 이미 발급했는지(중복 발급 방지). */
    boolean existsByMemberIdAndCouponId(Long memberId, Long couponId);

    /** 특정 상태로 보유한 회원 ID 목록(전체 발급 시 이미 가진 회원 거르기 등엔 별도 처리). */
    List<MemberCoupon> findByCouponIdAndStatus(Long couponId, MemberCouponStatus status);
}
