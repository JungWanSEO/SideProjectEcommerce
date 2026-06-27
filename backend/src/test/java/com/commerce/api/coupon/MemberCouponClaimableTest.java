package com.commerce.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.coupon.dto.ClaimableCouponResponse;
import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponIssueType;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.repository.CouponRepository;
import com.commerce.api.coupon.service.MemberCouponService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 받을 수 있는(claimable) 쿠폰 목록 조회 테스트.
 *
 * <p>{@code getClaimableCoupons}가 (1) 발급형·활성·기간 내 쿠폰만 추리고, (2) 회원별
 * {@code alreadyClaimed}와 (3) 잔여수량/마감을 정확히 채우는지 검증한다.
 *
 * <p><b>commit 스타일(@Transactional 미사용):</b> claim()의 원자적 조건부 UPDATE는 @Modifying이라
 * 같은 트랜잭션의 1차 캐시 엔티티는 stale하다. 테스트를 한 트랜잭션으로 묶으면 issuedCount가 갱신 전 값으로
 * 읽혀 잔여수량 검증이 깨진다. {@link CouponClaimConcurrencyTest}와 동일하게 각 단계를 커밋시켜
 * getClaimableCoupons가 fresh 조회로 정확한 issuedCount를 보게 한다. (DB 오염은 id로 필터해 회피.)
 */
@SpringBootTest
class MemberCouponClaimableTest {

    @Autowired
    private MemberCouponService memberCouponService;
    @Autowired
    private CouponRepository couponRepository;

    private static final long ME = 9001L;
    private static final long OTHER = 9002L;
    private static final AtomicLong SEQ = new AtomicLong();   // 코드 유일성 보장(unique 제약)

    @Test
    @DisplayName("받을 수 있는 쿠폰 - 발급형·활성·기간 내만, 회원별 alreadyClaimed·잔여수량·마감 표시")
    void claimable_filtersAndFlags() {
        LocalDateTime now = LocalDateTime.now();

        Long unlimited = save(issued(null, now.minusDays(1), now.plusDays(1)));    // 무제한
        Long limited = save(issued(5, now.minusDays(1), now.plusDays(1)));         // 한정 5장(아무도 안 받음)
        Long soldOut = save(issued(1, now.minusDays(1), now.plusDays(1)));         // 한정 1장 → 소진시킬 것
        Long claimedByMe = save(issued(10, now.minusDays(1), now.plusDays(1)));    // 내가 받을 것
        Long publicCoupon = save(publicCode(now.minusDays(1), now.plusDays(1)));   // 공개형 → 제외
        Long expired = save(issued(10, now.minusDays(10), now.minusDays(1)));      // 기간 외 → 제외
        Coupon disabledCoupon = issued(10, now.minusDays(1), now.plusDays(1));
        disabledCoupon.disable();
        Long disabled = save(disabledCoupon);                                      // 비활성 → 제외

        // 소진: 다른 회원이 1장짜리를 받아 마감시킨다. 내가 직접 한 장 받는다.
        memberCouponService.claim(OTHER, soldOut);
        memberCouponService.claim(ME, claimedByMe);

        Map<Long, ClaimableCouponResponse> byId = memberCouponService.getClaimableCoupons(ME).stream()
                .collect(Collectors.toMap(ClaimableCouponResponse::id, Function.identity()));

        // 포함: 발급형 + 활성 + 기간 내
        assertThat(byId).containsKeys(unlimited, limited, soldOut, claimedByMe);
        // 제외: 공개형 / 기간 외 / 비활성
        assertThat(byId).doesNotContainKeys(publicCoupon, expired, disabled);

        // 무제한: remaining=null, soldOut=false
        assertThat(byId.get(unlimited).remainingQuantity()).isNull();
        assertThat(byId.get(unlimited).soldOut()).isFalse();
        // 한정 5장, 미발급: remaining=5
        assertThat(byId.get(limited).remainingQuantity()).isEqualTo(5);
        assertThat(byId.get(limited).soldOut()).isFalse();
        assertThat(byId.get(limited).alreadyClaimed()).isFalse();
        // 마감: remaining=0, soldOut=true (내가 받은 건 아니므로 alreadyClaimed=false)
        assertThat(byId.get(soldOut).remainingQuantity()).isEqualTo(0);
        assertThat(byId.get(soldOut).soldOut()).isTrue();
        assertThat(byId.get(soldOut).alreadyClaimed()).isFalse();
        // 내가 받음: alreadyClaimed=true, 잔여 10→9
        assertThat(byId.get(claimedByMe).alreadyClaimed()).isTrue();
        assertThat(byId.get(claimedByMe).remainingQuantity()).isEqualTo(9);
    }

    private Long save(Coupon c) {
        return couponRepository.save(c).getId();
    }

    private Coupon issued(Integer totalQuantity, LocalDateTime from, LocalDateTime until) {
        return Coupon.create("CL-" + SEQ.incrementAndGet() + "-" + System.nanoTime(), "받기 쿠폰",
                DiscountType.FIXED_AMOUNT, 3000, null, 0, CouponFundedBy.PLATFORM, null,
                CouponIssueType.ISSUED, from, until, totalQuantity);
    }

    private Coupon publicCode(LocalDateTime from, LocalDateTime until) {
        return Coupon.create("PUB-" + SEQ.incrementAndGet() + "-" + System.nanoTime(), "공개 쿠폰",
                DiscountType.FIXED_AMOUNT, 3000, null, 0, CouponFundedBy.PLATFORM, null,
                CouponIssueType.PUBLIC, from, until);
    }
}
