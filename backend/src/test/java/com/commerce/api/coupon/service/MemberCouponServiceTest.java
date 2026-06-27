package com.commerce.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.coupon.dto.CouponApplyResult;
import com.commerce.api.coupon.dto.CouponIssueRequest;
import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponIssueType;
import com.commerce.api.coupon.entity.CouponStatus;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.entity.MemberCoupon;
import com.commerce.api.coupon.entity.MemberCouponStatus;
import com.commerce.api.coupon.repository.CouponRepository;
import com.commerce.api.coupon.repository.MemberCouponRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MemberCouponService 단위 테스트 — 발급·지갑 적용(공개형/발급형 분기·단일 사용)·복원.
 */
@ExtendWith(MockitoExtension.class)
class MemberCouponServiceTest {

    @Mock
    private MemberCouponRepository memberCouponRepository;
    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponService couponService;
    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberCouponService memberCouponService;

    private static final LocalDateTime FROM = LocalDateTime.now().minusDays(1);
    private static final LocalDateTime UNTIL = LocalDateTime.now().plusDays(1);

    private Coupon coupon(Long id, CouponIssueType issueType) {
        Coupon c = Coupon.create("CODE", "쿠폰", DiscountType.FIXED_AMOUNT, 5000, null, 0,
                CouponFundedBy.PLATFORM, null, issueType, FROM, UNTIL);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private Member member(Long id) {
        Member m = Member.builder().email("m" + id + "@x.com").nickname("n").role(Role.USER).build();
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    private CouponApplyResult result() {
        return new CouponApplyResult("CODE", 5000L, CouponFundedBy.PLATFORM, null);
    }

    @Test
    @DisplayName("적용 - 공개형(PUBLIC)은 지갑 검증 없이 코드만으로 적용")
    void apply_public_noWallet() {
        Coupon c = coupon(8L, CouponIssueType.PUBLIC);
        given(couponService.findByCode("CODE")).willReturn(c);
        given(couponService.calculateDiscount(eq(c), eq(20000L), anyMap())).willReturn(result());

        CouponApplyResult r = memberCouponService.apply(100L, "CODE", 20000L, Map.of());

        assertThat(r.discountAmount()).isEqualTo(5000L);
        verify(memberCouponRepository, never()).findByMemberIdAndCouponId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("적용 - 발급형(ISSUED)은 보유(미사용) 검증 후 USED로 잠금(단일 사용)")
    void apply_issued_marksUsed() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        given(couponService.findByCode("CODE")).willReturn(c);
        given(couponService.calculateDiscount(eq(c), eq(20000L), anyMap())).willReturn(result());
        MemberCoupon mc = MemberCoupon.issue(100L, 7L);
        given(memberCouponRepository.findByMemberIdAndCouponId(100L, 7L)).willReturn(Optional.of(mc));

        CouponApplyResult r = memberCouponService.apply(100L, "CODE", 20000L, Map.of());

        assertThat(r.discountAmount()).isEqualTo(5000L);
        assertThat(mc.getStatus()).isEqualTo(MemberCouponStatus.USED);
    }

    @Test
    @DisplayName("적용 실패 - 발급형인데 발급받지 않았으면 400")
    void apply_issued_notOwned() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        given(couponService.findByCode("CODE")).willReturn(c);
        given(couponService.calculateDiscount(eq(c), anyLong(), anyMap())).willReturn(result());
        given(memberCouponRepository.findByMemberIdAndCouponId(100L, 7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberCouponService.apply(100L, "CODE", 20000L, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("발급받지 않은");
    }

    @Test
    @DisplayName("적용 실패 - 발급형인데 이미 사용했으면 400")
    void apply_issued_alreadyUsed() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        given(couponService.findByCode("CODE")).willReturn(c);
        given(couponService.calculateDiscount(eq(c), anyLong(), anyMap())).willReturn(result());
        MemberCoupon used = MemberCoupon.issue(100L, 7L);
        used.markUsed();
        given(memberCouponRepository.findByMemberIdAndCouponId(100L, 7L)).willReturn(Optional.of(used));

        assertThatThrownBy(() -> memberCouponService.apply(100L, "CODE", 20000L, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 사용");
    }

    @Test
    @DisplayName("발급 - 전체 회원에게(이미 발급된 회원은 건너뜀)")
    void issue_toAll_skipsExisting() {
        given(couponRepository.findById(7L)).willReturn(Optional.of(coupon(7L, CouponIssueType.ISSUED)));
        given(memberRepository.findAll()).willReturn(List.of(member(1L), member(2L)));
        given(memberCouponRepository.existsByMemberIdAndCouponId(1L, 7L)).willReturn(false);
        given(memberCouponRepository.existsByMemberIdAndCouponId(2L, 7L)).willReturn(true);   // 이미 발급
        given(memberCouponRepository.save(any(MemberCoupon.class))).willAnswer(inv -> inv.getArgument(0));

        int issued = memberCouponService.issue(7L, new CouponIssueRequest(true, null));

        assertThat(issued).isEqualTo(1);   // 회원1만 신규 발급
        verify(memberCouponRepository).save(any(MemberCoupon.class));
    }

    @Test
    @DisplayName("발급 실패 - 공개형(PUBLIC) 쿠폰은 회원 발급 불가 400")
    void issue_publicRejected() {
        given(couponRepository.findById(8L)).willReturn(Optional.of(coupon(8L, CouponIssueType.PUBLIC)));

        assertThatThrownBy(() -> memberCouponService.issue(8L, new CouponIssueRequest(true, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("발급형(ISSUED)");
    }

    // ----- 선착순 받기(claim) -----

    @Test
    @DisplayName("선착순 받기 성공 - 한도 내(원자적 증가=1)면 발급")
    void claim_success() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        given(couponRepository.findById(7L)).willReturn(Optional.of(c));
        given(memberCouponRepository.existsByMemberIdAndCouponId(100L, 7L)).willReturn(false);
        given(couponRepository.incrementIssuedCount(7L)).willReturn(1);   // 한도 내
        given(memberCouponRepository.save(any(MemberCoupon.class))).willAnswer(inv -> inv.getArgument(0));

        var response = memberCouponService.claim(100L, 7L);

        assertThat(response).isNotNull();
        verify(memberCouponRepository).save(any(MemberCoupon.class));
    }

    @Test
    @DisplayName("선착순 받기 실패 - 마감(원자적 증가=0)이면 409, 발급 저장 안 함")
    void claim_soldOut() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        given(couponRepository.findById(7L)).willReturn(Optional.of(c));
        given(memberCouponRepository.existsByMemberIdAndCouponId(100L, 7L)).willReturn(false);
        given(couponRepository.incrementIssuedCount(7L)).willReturn(0);   // 소진

        assertThatThrownBy(() -> memberCouponService.claim(100L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("마감");
        verify(memberCouponRepository, never()).save(any());
    }

    @Test
    @DisplayName("선착순 받기 실패 - 이미 받았으면 409, 카운터 증가 안 함")
    void claim_alreadyClaimed() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        given(couponRepository.findById(7L)).willReturn(Optional.of(c));
        given(memberCouponRepository.existsByMemberIdAndCouponId(100L, 7L)).willReturn(true);

        assertThatThrownBy(() -> memberCouponService.claim(100L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 받은");
        verify(couponRepository, never()).incrementIssuedCount(anyLong());
    }

    @Test
    @DisplayName("선착순 받기 실패 - 공개형(PUBLIC)은 직접 받기 불가 400")
    void claim_publicRejected() {
        given(couponRepository.findById(8L)).willReturn(Optional.of(coupon(8L, CouponIssueType.PUBLIC)));

        assertThatThrownBy(() -> memberCouponService.claim(100L, 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("발급형(ISSUED)");
    }

    @Test
    @DisplayName("선착순 받기 실패 - 없는 쿠폰 404")
    void claim_notFound() {
        given(couponRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberCouponService.claim(100L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("선착순 받기 실패 - 비활성 쿠폰이면 400(claimable 아님)")
    void claim_disabled() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        ReflectionTestUtils.setField(c, "status", CouponStatus.DISABLED);
        given(couponRepository.findById(7L)).willReturn(Optional.of(c));

        assertThatThrownBy(() -> memberCouponService.claim(100L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("받을 수 없는");
        verify(couponRepository, never()).incrementIssuedCount(anyLong());
    }

    @Test
    @DisplayName("복원 - 주문 취소 시 발급형 쿠폰을 미사용으로 되돌림")
    void release_restoresUnused() {
        Coupon c = coupon(7L, CouponIssueType.ISSUED);
        given(couponService.findByCodeOptional("CODE")).willReturn(Optional.of(c));
        MemberCoupon used = MemberCoupon.issue(100L, 7L);
        used.markUsed();
        given(memberCouponRepository.findByMemberIdAndCouponId(100L, 7L)).willReturn(Optional.of(used));

        memberCouponService.release(100L, "CODE");

        assertThat(used.getStatus()).isEqualTo(MemberCouponStatus.UNUSED);
    }
}
