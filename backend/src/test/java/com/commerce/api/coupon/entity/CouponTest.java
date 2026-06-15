package com.commerce.api.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Coupon 엔티티 단위 테스트 — 할인 계산(정액/정률/상한)과 사용 가능 검증, 생성 불변식.
 * (시간 의존 검증은 now를 인자로 주입받아 비-flaky.)
 */
class CouponTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 12, 0);
    private static final LocalDateTime FROM = NOW.minusDays(1);
    private static final LocalDateTime UNTIL = NOW.plusDays(1);

    private Coupon fixed(long value, long minOrder) {
        return Coupon.create("WELCOME", "정액", DiscountType.FIXED_AMOUNT, value, null, minOrder,
                CouponFundedBy.PLATFORM, null, FROM, UNTIL);
    }

    private Coupon percentCoupon(long percent, Long cap) {
        return Coupon.create("SALE", "정률", DiscountType.PERCENTAGE, percent, cap, 0L,
                CouponFundedBy.SELLER, 3L, FROM, UNTIL);
    }

    @Nested
    @DisplayName("할인 계산")
    class Calculate {

        @Test
        @DisplayName("정액 - 고정 금액을 깎는다")
        void fixedAmount() {
            assertThat(fixed(5000, 0).calculateDiscount(20000)).isEqualTo(5000);
        }

        @Test
        @DisplayName("정액 - 적용 대상 금액을 넘기지 않는다(음수 결제 방지)")
        void fixedAmountCappedByApplicable() {
            assertThat(fixed(5000, 0).calculateDiscount(3000)).isEqualTo(3000);
        }

        @Test
        @DisplayName("정률 - 퍼센트만큼 깎고 원 단위 내림")
        void percentage() {
            assertThat(percentCoupon(10L, null).calculateDiscount(20000)).isEqualTo(2000);
            assertThat(percentCoupon(10L, null).calculateDiscount(19999)).isEqualTo(1999);   // 1999.9 → 내림
        }

        @Test
        @DisplayName("정률 - 상한(maxDiscountAmount)에서 컷")
        void percentageWithCap() {
            assertThat(percentCoupon(10L, 10000L).calculateDiscount(200000)).isEqualTo(10000);   // 20000이지만 상한 10000
        }
    }

    @Nested
    @DisplayName("사용 가능 검증")
    class Validate {

        @Test
        @DisplayName("활성·기간 내·최소금액 충족이면 통과")
        void usable() {
            assertThatCode(() -> fixed(5000, 10000).validateUsable(20000, NOW)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("최소 주문금액 미달이면 400")
        void belowMinOrder() {
            assertThatThrownBy(() -> fixed(5000, 30000).validateUsable(20000, NOW))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("최소 주문금액");
        }

        @Test
        @DisplayName("적용 대상 금액이 0이면 400(셀러 한정 쿠폰인데 그 셀러 상품 없음 등)")
        void noApplicableAmount() {
            assertThatThrownBy(() -> fixed(5000, 0).validateUsable(0, NOW))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("적용할 수 있는 상품이 없습니다");
        }

        @Test
        @DisplayName("기간 외(시작 전/종료 후)면 400")
        void outsideWindow() {
            assertThatThrownBy(() -> fixed(5000, 0).validateUsable(20000, FROM.minusDays(1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("사용 기간");
            assertThatThrownBy(() -> fixed(5000, 0).validateUsable(20000, UNTIL.plusDays(1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("사용 기간");
        }

        @Test
        @DisplayName("비활성(disable)이면 400")
        void disabled() {
            Coupon coupon = fixed(5000, 0);
            coupon.disable();
            assertThatThrownBy(() -> coupon.validateUsable(20000, NOW))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("사용할 수 없는 쿠폰");
        }
    }

    @Nested
    @DisplayName("생성 불변식")
    class Invariants {

        @Test
        @DisplayName("유효기간 역전(시작 > 종료)이면 400")
        void invalidWindow() {
            assertThatThrownBy(() -> Coupon.create("X", "n", DiscountType.FIXED_AMOUNT, 1000, null, 0L,
                    CouponFundedBy.PLATFORM, null, UNTIL, FROM))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("유효기간");
        }

        @Test
        @DisplayName("정률 100% 초과면 400")
        void percentOver100() {
            assertThatThrownBy(() -> Coupon.create("X", "n", DiscountType.PERCENTAGE, 101, null, 0L,
                    CouponFundedBy.PLATFORM, null, FROM, UNTIL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("100%");
        }

        @Test
        @DisplayName("할인 값이 0 이하면 400")
        void nonPositiveValue() {
            assertThatThrownBy(() -> Coupon.create("X", "n", DiscountType.FIXED_AMOUNT, 0, null, 0L,
                    CouponFundedBy.PLATFORM, null, FROM, UNTIL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("0보다 커야");
        }
    }
}
