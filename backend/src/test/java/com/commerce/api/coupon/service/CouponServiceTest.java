package com.commerce.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.coupon.dto.CouponApplyResult;
import com.commerce.api.coupon.dto.CouponCreateRequest;
import com.commerce.api.coupon.dto.CouponResponse;
import com.commerce.api.coupon.entity.CouponStatus;
import com.commerce.api.coupon.entity.MemberCouponStatus;
import org.springframework.test.util.ReflectionTestUtils;
import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponIssueType;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.repository.CouponRepository;
import com.commerce.api.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/**
 * CouponService 단위 테스트 — 발급(코드 정규화·중복)과 체크아웃 적용(플랫폼/셀러 범위·미존재).
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private com.commerce.api.coupon.repository.MemberCouponRepository memberCouponRepository;

    @InjectMocks
    private CouponService couponService;

    private static final LocalDateTime FROM = LocalDateTime.now().minusDays(1);
    private static final LocalDateTime UNTIL = LocalDateTime.now().plusDays(1);

    private Coupon coupon(String code, DiscountType type, long value, Long cap, long min,
            CouponFundedBy funded, Long sellerId) {
        return Coupon.create(code, "이름", type, value, cap, min, funded, sellerId,
                CouponIssueType.PUBLIC, FROM, UNTIL);
    }

    private CouponCreateRequest request(String code, CouponFundedBy funded, Long sellerId) {
        return new CouponCreateRequest(code, "신규 5천원", DiscountType.FIXED_AMOUNT, 5000L, null,
                30000L, funded, CouponIssueType.PUBLIC, sellerId, FROM, UNTIL);
    }

    @Test
    @DisplayName("발급 - 코드를 대문자로 정규화해 저장한다")
    void create_normalizesCode() {
        given(couponRepository.existsByCode("WELCOME5000")).willReturn(false);
        given(couponRepository.save(any(Coupon.class))).willAnswer(inv -> inv.getArgument(0));

        CouponResponse res = couponService.create(request("welcome5000", CouponFundedBy.PLATFORM, null));

        assertThat(res.code()).isEqualTo("WELCOME5000");
        assertThat(res.discountType()).isEqualTo(DiscountType.FIXED_AMOUNT);
        assertThat(res.fundedBy()).isEqualTo(CouponFundedBy.PLATFORM);
    }

    @Test
    @DisplayName("발급 실패 - 코드 중복이면 409, 저장 안 함")
    void create_duplicate() {
        given(couponRepository.existsByCode("DUP")).willReturn(true);

        assertThatThrownBy(() -> couponService.create(request("dup", CouponFundedBy.PLATFORM, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 존재");
        verify(couponRepository, never()).save(any());
    }

    @Test
    @DisplayName("할인 계산 - 플랫폼 와이드(sellerId=null)는 주문 총액 기준")
    void calc_platformWide() {
        Coupon c = coupon("SAVE", DiscountType.FIXED_AMOUNT, 5000, null, 10000, CouponFundedBy.PLATFORM, null);

        CouponApplyResult r = couponService.calculateDiscount(c, 20000L, Map.of());

        assertThat(r.code()).isEqualTo("SAVE");
        assertThat(r.discountAmount()).isEqualTo(5000L);
        assertThat(r.fundedBy()).isEqualTo(CouponFundedBy.PLATFORM);
        assertThat(r.sellerId()).isNull();
    }

    @Test
    @DisplayName("할인 계산 - 셀러 한정은 그 셀러 상품 소계 기준(다른 셀러 소계는 무시)")
    void calc_sellerScoped() {
        Coupon c = coupon("BRAND10", DiscountType.PERCENTAGE, 10, null, 0, CouponFundedBy.SELLER, 3L);
        // 셀러3 소계 20000, 셀러5 소계 30000 → 셀러3 한정이면 20000의 10% = 2000
        Map<Long, Long> grossBySeller = Map.of(3L, 20000L, 5L, 30000L);

        CouponApplyResult r = couponService.calculateDiscount(c, 50000L, grossBySeller);

        assertThat(r.discountAmount()).isEqualTo(2000L);
        assertThat(r.fundedBy()).isEqualTo(CouponFundedBy.SELLER);
        assertThat(r.sellerId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("코드 조회 실패 - 존재하지 않는 코드면 400")
    void findByCode_notFound() {
        given(couponRepository.findByCode("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> couponService.findByCode("nope"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("존재하지 않는");
    }

    @Test
    @DisplayName("할인 계산 실패 - 셀러 한정인데 그 셀러 상품이 주문에 없으면 400")
    void calc_sellerNotInOrder() {
        Coupon c = coupon("BRAND10", DiscountType.PERCENTAGE, 10, null, 0, CouponFundedBy.SELLER, 3L);
        Map<Long, Long> grossBySeller = Map.of(5L, 30000L);   // 셀러3 없음

        assertThatThrownBy(() -> couponService.calculateDiscount(c, 30000L, grossBySeller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("적용할 수 있는 상품이 없습니다");
    }

    @Test
    @DisplayName("목록 - 최신순 조회 + 쿠폰별 사용 수를 배치 집계로 채운다")
    void getCoupons() {
        Coupon a = coupon("A", DiscountType.FIXED_AMOUNT, 1000, null, 0, CouponFundedBy.PLATFORM, null);
        ReflectionTestUtils.setField(a, "id", 10L);
        given(couponRepository.findAll(any(Sort.class))).willReturn(List.of(a));
        given(memberCouponRepository.countByStatusGroupByCoupon(MemberCouponStatus.USED))
                .willReturn(List.<Object[]>of(new Object[]{10L, 3L}));   // 쿠폰10 사용 3건

        var coupons = couponService.getCoupons();

        assertThat(coupons).hasSize(1);
        assertThat(coupons.get(0).usedCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("중단 - 상태를 DISABLED로 바꾼다(기간 남아도 즉시 사용 불가)")
    void disable_setsDisabled() {
        Coupon c = coupon("OOPS", DiscountType.PERCENTAGE, 90, null, 0, CouponFundedBy.PLATFORM, null);
        ReflectionTestUtils.setField(c, "id", 7L);
        given(couponRepository.findById(7L)).willReturn(java.util.Optional.of(c));

        CouponResponse response = couponService.disable(7L);

        assertThat(response.status()).isEqualTo(CouponStatus.DISABLED);
        assertThat(c.getStatus()).isEqualTo(CouponStatus.DISABLED);
    }

    @Test
    @DisplayName("중단 실패 - 없는 쿠폰이면 404")
    void disable_notFound() {
        given(couponRepository.findById(999L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> couponService.disable(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
