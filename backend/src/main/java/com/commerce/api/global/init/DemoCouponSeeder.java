package com.commerce.api.global.init;

import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponIssueType;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.entity.MemberCoupon;
import com.commerce.api.coupon.repository.CouponRepository;
import com.commerce.api.coupon.repository.MemberCouponRepository;
import com.commerce.api.member.entity.Member;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 데모 쿠폰 시드(dev 전용) — 쿠폰/프로모션은 이 프로젝트의 차별점(플랫폼·셀러 <b>부담 주체</b> 회계, 선착순 동시성)인데
 * 데모 데이터가 없으면 화면이 텅 비어 그 설계가 보이지 않는다.
 *
 * <p>네 장으로 축을 모두 덮는다: <b>정액</b>(플랫폼 부담) · <b>정률+상한</b>(플랫폼) · <b>셀러 한정</b>(셀러 부담 —
 * 정산에서 셀러 gross가 줄어드는 경로) · <b>선착순 발급형</b>(회원 쿠폰함 + 한정 수량). 발급형은 데모 회원에게
 * 실제로 발급해 쿠폰함·잔여 수량이 살아 있게 한다.
 *
 * <p>멱등: 코드가 자연키라 있으면 건너뛰고, 발급도 (회원, 쿠폰) 보유 여부를 먼저 본다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
class DemoCouponSeeder {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    /** 선착순 발급형 쿠폰 코드 — 데모 회원에게 실제로 발급해 쿠폰함을 채운다. */
    private static final String ISSUED_CODE = "FIRSTCOME10K";

    /**
     * 데모 쿠폰을 보장한다.
     *
     * @param sellerId 셀러 한정 쿠폰을 붙일 셀러(없으면 그 쿠폰은 건너뛴다)
     * @param members  발급형 쿠폰을 나눠 가질 데모 회원
     */
    void seed(Long sellerId, List<Member> members) {
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime until = LocalDateTime.now().plusDays(90);

        ensure("WELCOME5000", () -> Coupon.create("WELCOME5000", "신규 가입 5,000원 할인",
                DiscountType.FIXED_AMOUNT, 5_000L, null, 30_000L,
                CouponFundedBy.PLATFORM, null, CouponIssueType.PUBLIC, from, until));

        ensure("SPRING10", () -> Coupon.create("SPRING10", "시즌 10% 할인 (최대 2만원)",
                DiscountType.PERCENTAGE, 10L, 20_000L, 50_000L,
                CouponFundedBy.PLATFORM, null, CouponIssueType.PUBLIC, from, until));

        if (sellerId != null) {
            // 셀러 부담(funded-by=SELLER) — 정산에서 이 셀러의 gross가 할인만큼 줄어드는 경로를 데모에서 보여준다.
            ensure("MAISON15", () -> Coupon.create("MAISON15", "메종클레이 단독 15% 할인",
                    DiscountType.PERCENTAGE, 15L, 30_000L, 0L,
                    CouponFundedBy.SELLER, sellerId, CouponIssueType.PUBLIC, from, until));
        }

        Coupon firstCome = ensure(ISSUED_CODE, () -> Coupon.create(ISSUED_CODE, "선착순 한정 1만원 할인",
                DiscountType.FIXED_AMOUNT, 10_000L, null, 50_000L,
                CouponFundedBy.PLATFORM, null, CouponIssueType.ISSUED, from, until, 100));

        int issued = issueTo(firstCome, members);
        if (issued > 0) {
            log.info("[demo-seed] 데모 쿠폰 준비 — 발급형 {}건 회원 쿠폰함에 배포", issued);
        }
    }

    /** 코드가 자연키 — 없으면 만들고 있으면 그대로 둔다(운영 중 수정한 값을 되돌리지 않는다). */
    private Coupon ensure(String code, java.util.function.Supplier<Coupon> factory) {
        return couponRepository.findByCode(code).orElseGet(() -> couponRepository.save(factory.get()));
    }

    /** 발급형 쿠폰을 회원 쿠폰함에 넣는다(이미 보유하면 건너뜀). 잔여 수량 카운터도 함께 올린다. */
    private int issueTo(Coupon coupon, List<Member> members) {
        int issued = 0;
        for (Member member : members) {
            if (memberCouponRepository.findByMemberIdAndCouponId(member.getId(), coupon.getId()).isPresent()) {
                continue;
            }
            memberCouponRepository.save(MemberCoupon.issue(member.getId(), coupon.getId()));
            couponRepository.incrementIssuedCount(coupon.getId());   // 한도 내에서만 +1(원자 UPDATE)
            issued++;
        }
        return issued;
    }
}
