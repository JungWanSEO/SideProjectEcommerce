package com.commerce.api.coupon.service;

import com.commerce.api.coupon.dto.MemberCouponResponse;
import com.commerce.api.global.lock.DistributedLock;
import com.commerce.api.global.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 선착순 쿠폰 받기 진입점 — {@link MemberCouponService#claim}을 <b>분산락으로 감싸</b> 다중 인스턴스에서도
 * 같은 쿠폰 요청을 앱 계층에서 직렬화한다.
 *
 * <p><b>왜 별도 빈인가:</b> 락은 트랜잭션을 통째로 감싸야 한다(획득 → tx → 커밋 → 해제). {@code MemberCouponService}는
 * 클래스 레벨 {@code @Transactional}이라 같은 빈 안에서 감싸면 (자기호출 프록시 우회 + readOnly tx 중첩) 문제가 생긴다.
 * 별도 빈에서 호출하면 {@code claim}의 {@code @Transactional} 프록시가 정상 적용되고, 락이 그 tx 전체를 감싼다.
 *
 * <p><b>역할:</b> 락은 advisory(경합 완화)이고, 초과 발급 방지의 최종 보증은 {@code claim} 내부의 DB 원자적
 * 조건부 UPDATE다(락이 꺼져도 정합성은 유지). 기본은 NoOp 락이라 동작이 기존과 동일하다.
 */
@Service
@RequiredArgsConstructor
public class MemberCouponClaimService {

    private final DistributedLock distributedLock;
    private final MemberCouponService memberCouponService;
    private final RateLimiter rateLimiter;

    /** 회원별 레이트 리밋 후, 쿠폰별 락("coupon:claim:{id}")으로 직렬화해 실제 발급(트랜잭션)을 수행한다. */
    public MemberCouponResponse claim(Long memberId, Long couponId) {
        rateLimiter.check("claim:" + memberId, 20);   // claim 스팸 방지: 회원당 1분 20회
        return distributedLock.executeWithLock(
                "coupon:claim:" + couponId,
                () -> memberCouponService.claim(memberId, couponId));
    }
}
