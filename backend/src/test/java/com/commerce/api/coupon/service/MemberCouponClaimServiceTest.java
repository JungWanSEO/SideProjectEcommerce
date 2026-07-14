package com.commerce.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commerce.api.coupon.dto.MemberCouponResponse;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.entity.MemberCouponStatus;
import com.commerce.api.global.lock.DistributedLock;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MemberCouponClaimService 단위 테스트 — 선착순 claim의 <b>진입점</b>(분산락 + 위임).
 *
 * <p><b>왜 이제야</b>: JaCoCo(07-14)에서 이 클래스가 <b>0%</b>로 드러났다. 동시성 통합 테스트는 안쪽
 * {@code MemberCouponService.claim}(DB 원자 UPDATE)만 치고, 그 위를 감싸는 <b>락 진입점</b>은 아무도 안 쳤다 —
 * 락 키가 쿠폰별이 아니라면(예: 전역 키) 처리량이 무너지고, 위임이 빠지면 발급 자체가 안 된다.
 */
@ExtendWith(MockitoExtension.class)
class MemberCouponClaimServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long COUPON_ID = 9L;

    @Mock private DistributedLock distributedLock;
    @Mock private MemberCouponService memberCouponService;

    @InjectMocks private MemberCouponClaimService claimService;

    private MemberCouponResponse issued() {
        LocalDateTime now = LocalDateTime.now();
        return new MemberCouponResponse(100L, COUPON_ID, "WELCOME10", "웰컴 쿠폰",
                DiscountType.FIXED_AMOUNT, 3_000L, null, 10_000L, CouponFundedBy.PLATFORM, null,
                now.minusDays(1), now.plusDays(7), MemberCouponStatus.UNUSED, null, true);
    }

    @Test
    @DisplayName("claim - 쿠폰별 락('coupon:claim:{couponId}') 안에서 발급을 위임하고 결과를 그대로 반환한다")
    void claim_wrapsWithCouponScopedLock() {
        given(memberCouponService.claim(MEMBER_ID, COUPON_ID)).willReturn(issued());
        // 락 어댑터는 넘겨받은 작업을 그대로 실행한다(NoOp 락과 동일한 성질).
        given(distributedLock.executeWithLock(anyString(), any()))
                .willAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

        MemberCouponResponse response = claimService.claim(MEMBER_ID, COUPON_ID);

        assertThat(response.couponId()).isEqualTo(COUPON_ID);

        // 락 키는 쿠폰 단위여야 한다 — 전역 키면 서로 다른 쿠폰끼리도 줄을 서 처리량이 무너진다.
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(distributedLock).executeWithLock(key.capture(), any());
        assertThat(key.getValue()).isEqualTo("coupon:claim:" + COUPON_ID);
        verify(memberCouponService).claim(MEMBER_ID, COUPON_ID);
    }
}
