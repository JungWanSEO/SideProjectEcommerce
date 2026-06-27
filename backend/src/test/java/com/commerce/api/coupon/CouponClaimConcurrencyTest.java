package com.commerce.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponIssueType;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.entity.MemberCouponStatus;
import com.commerce.api.coupon.repository.CouponRepository;
import com.commerce.api.coupon.repository.MemberCouponRepository;
import com.commerce.api.coupon.service.MemberCouponService;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 선착순 쿠폰 발급 동시성 통합 테스트.
 *
 * <p>한도 N장짜리 쿠폰을 여러 회원이 동시에 받아도 <b>초과 발급이 없음</b>을 검증한다.
 * 발급 가드 = {@code CouponRepository.incrementIssuedCount}의 원자적 조건부 UPDATE
 * (한도 내일 때만 +1, DB 행 락으로 직렬화) — 애플리케이션 락 없이 lost update를 막는다.
 */
@SpringBootTest
class CouponClaimConcurrencyTest {

    @Autowired
    private MemberCouponService memberCouponService;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private MemberCouponRepository memberCouponRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("선착순 동시 발급 - 초과 발급 없이 정확히 한도(N장)만 발급된다")
    void concurrentClaim_noOverIssue() throws InterruptedException {
        // given: 한도 10장짜리 발급형 쿠폰. 30명이 동시에 받기 시도.
        int limit = 10;
        int threadCount = 30;
        Coupon coupon = Coupon.create(
                "FLASH-" + System.nanoTime(), "선착순 쿠폰", DiscountType.FIXED_AMOUNT, 5000, null, 0,
                CouponFundedBy.PLATFORM, null, CouponIssueType.ISSUED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), limit);
        Long couponId = couponRepository.save(coupon).getId();

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        // when: 30명(서로 다른 회원 ID)이 동시에 같은 쿠폰을 받는다.
        for (int i = 0; i < threadCount; i++) {
            long memberId = 1000 + i;
            executor.submit(() -> {
                try {
                    memberCouponService.claim(memberId, couponId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();   // 마감(409) 등
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 정확히 한도만큼만 발급 — 초과 발급 0
        int issuedCount = new TransactionTemplate(txManager).execute(s ->
                couponRepository.findById(couponId).orElseThrow().getIssuedCount());
        int actualIssued = memberCouponRepository
                .findByCouponIdAndStatus(couponId, MemberCouponStatus.UNUSED).size();

        assertThat(success.get()).isEqualTo(limit);                  // ① 성공 = 한도
        assertThat(fail.get()).isEqualTo(threadCount - limit);       // ② 나머지는 마감
        assertThat(issuedCount).isEqualTo(limit);                    // ③ 카운터 = 한도(초과 증가 없음)
        assertThat(actualIssued).isEqualTo(limit);                   // ④ 실제 발급 행 = 한도(초과 발급 없음)
    }
}
