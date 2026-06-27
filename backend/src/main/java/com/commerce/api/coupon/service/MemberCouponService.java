package com.commerce.api.coupon.service;

import com.commerce.api.coupon.dto.ClaimableCouponResponse;
import com.commerce.api.coupon.dto.CouponApplyResult;
import com.commerce.api.coupon.dto.CouponIssueRequest;
import com.commerce.api.coupon.dto.MemberCouponResponse;
import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.entity.MemberCoupon;
import com.commerce.api.coupon.repository.CouponRepository;
import com.commerce.api.coupon.repository.MemberCouponRepository;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 쿠폰함(MemberCoupon) — 발급형(ISSUED) 쿠폰의 보유·단일 사용·복원을 책임진다.
 *
 * <p>{@link CouponService}(할인 계산기) 위에서 <b>오케스트레이션</b>한다: 코드로 쿠폰을 찾고 할인을 계산하되,
 * <b>발급형이면 지갑 보유(미사용)를 검증</b>하고 사용 시 USED로 잠근다(단일 사용). 공개형(PUBLIC)은 지갑 없이
 * 코드만으로 적용(Step 1 동작). 주문 도메인은 {@link #apply}/{@link #preview}를, 취소 시 {@link #release}를 호출한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberCouponService {

    private final MemberCouponRepository memberCouponRepository;
    private final CouponRepository couponRepository;
    private final CouponService couponService;
    private final MemberRepository memberRepository;

    /**
     * 쿠폰 발급(ADMIN) — 발급형(ISSUED) 쿠폰을 전체 회원 또는 특정 이메일 회원에게 발급한다.
     * 이미 발급된 회원은 건너뛴다(회원·쿠폰당 1장). 새로 발급한 장수를 반환.
     */
    @Transactional
    public int issue(Long couponId, CouponIssueRequest request) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
        if (!coupon.isIssued()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "발급형(ISSUED) 쿠폰만 회원에게 발급할 수 있습니다.");
        }

        List<Long> memberIds;
        if (request.toAll()) {
            memberIds = memberRepository.findAll().stream().map(Member::getId).toList();
        } else {
            if (request.email() == null || request.email().isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "전체 발급이 아니면 회원 이메일이 필요합니다.");
            }
            Member member = memberRepository.findByEmail(request.email())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "해당 이메일의 회원을 찾을 수 없습니다."));
            memberIds = List.of(member.getId());
        }

        int issued = 0;
        for (Long memberId : memberIds) {
            if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
                continue;   // 이미 발급 — 중복 발급 안 함
            }
            memberCouponRepository.save(MemberCoupon.issue(memberId, couponId));
            issued++;
        }
        return issued;
    }

    /**
     * 선착순 쿠폰 받기(회원 직접) — 한도 내에서 회원당 1장 발급한다. 동시에 몰려도 초과 발급이 없다.
     *
     * <p><b>동시성 제어(핵심):</b>
     * <ol>
     *   <li><b>원자적 조건부 증가</b> {@code incrementIssuedCount} — 한도 내일 때만 DB가 행 단위로 +1.
     *       1이면 발급, 0이면 마감(409). 애플리케이션 락 없이 lost update를 구조적으로 차단.</li>
     *   <li><b>member_coupon UNIQUE</b>(회원·쿠폰) — 같은 회원 중복 발급 차단.</li>
     *   <li><b>한 트랜잭션</b> — 카운터 증가와 발급 저장이 원자적. 발급 저장이 실패하면(예: 중복)
     *       증가도 롤백되어 카운터와 실제 발급 수가 어긋나지 않는다.</li>
     * </ol>
     * 없는 쿠폰 404 · 발급형 아님/기간 외/비활성 400 · 이미 받음/마감 409.
     */
    @Transactional
    public MemberCouponResponse claim(Long memberId, Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
        if (!coupon.isIssued()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "발급형(ISSUED) 쿠폰만 받을 수 있습니다.");
        }
        LocalDateTime now = LocalDateTime.now();
        coupon.ensureClaimable(now);   // 활성 + 기간 내(수량은 아래 원자적 증가가 가드)
        if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 받은 쿠폰입니다.");
        }
        // ★ 동시성 핵심: 한도 내에서만 원자적으로 +1. 0이면 선착순 마감.
        if (couponRepository.incrementIssuedCount(couponId) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "선착순 마감되었습니다. (쿠폰이 모두 소진됨)");
        }
        MemberCoupon issued = memberCouponRepository.save(MemberCoupon.issue(memberId, couponId));
        return MemberCouponResponse.of(issued, coupon, now);
    }

    /**
     * 받을 수 있는(claimable) 선착순 쿠폰 목록 — 발급형 + 활성 + 기간 내 쿠폰을 회원 관점으로 내려준다.
     * 소진/이미받음도 거르지 않고 다 내리되(화면에서 '마감'·'받음' 비활성), 각 쿠폰에 이 회원의
     * {@code alreadyClaimed}를 표시한다. (보유 여부는 한 번의 in-조회로 모아 N+1을 피한다.)
     */
    public List<ClaimableCouponResponse> getClaimableCoupons(Long memberId) {
        List<Coupon> claimable = couponRepository.findClaimable(LocalDateTime.now());
        // 이 회원이 이미 보유한 쿠폰 ID 집합 — claimable 후보만 모아 한 번에 조회(쿠폰마다 exists 쿼리 X).
        Set<Long> ownedCouponIds = memberCouponRepository
                .findByMemberIdOrderByIdDesc(memberId).stream()
                .map(MemberCoupon::getCouponId)
                .collect(Collectors.toSet());
        return claimable.stream()
                .map(c -> ClaimableCouponResponse.of(c, ownedCouponIds.contains(c.getId())))
                .toList();
    }

    /** 내 쿠폰함(최신 발급 먼저) — 쿠폰 상세 enrich + 사용 가능 여부(usable). */
    public List<MemberCouponResponse> getMyWallet(Long memberId) {
        List<MemberCoupon> wallet = memberCouponRepository.findByMemberIdOrderByIdDesc(memberId);
        List<Long> couponIds = wallet.stream().map(MemberCoupon::getCouponId).distinct().toList();
        Map<Long, Coupon> coupons = couponRepository.findAllById(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, c -> c));
        LocalDateTime now = LocalDateTime.now();
        return wallet.stream()
                .filter(mc -> coupons.containsKey(mc.getCouponId()))
                .map(mc -> MemberCouponResponse.of(mc, coupons.get(mc.getCouponId()), now))
                .toList();
    }

    /**
     * 체크아웃 미리보기 — 할인을 계산해 돌려주되 사용 처리는 하지 않는다.
     * 발급형이면 보유(미사용)를 검증한다(없거나 사용했으면 400). 공개형은 코드만으로.
     */
    public CouponApplyResult preview(Long memberId, String code, long orderTotal, Map<Long, Long> grossBySeller) {
        Coupon coupon = couponService.findByCode(code);
        if (coupon.isIssued()) {
            requireOwnedUnused(memberId, coupon);
        }
        return couponService.calculateDiscount(coupon, orderTotal, grossBySeller);
    }

    /**
     * 체크아웃 적용 — 할인을 계산하고, 발급형이면 보유(미사용) 검증 후 USED로 잠근다(단일 사용).
     * 주문 생성 트랜잭션 안에서 호출되므로 사용 처리가 주문과 원자적이다(주문 실패 시 사용도 롤백).
     */
    @Transactional
    public CouponApplyResult apply(Long memberId, String code, long orderTotal, Map<Long, Long> grossBySeller) {
        Coupon coupon = couponService.findByCode(code);
        CouponApplyResult result = couponService.calculateDiscount(coupon, orderTotal, grossBySeller);
        if (coupon.isIssued()) {
            requireOwnedUnused(memberId, coupon).markUsed();   // 단일 사용 잠금(같은 tx에서 dirty-checking)
        }
        return result;
    }

    /**
     * 주문 취소 시 사용한 쿠폰 복원 — 발급형이면 그 회원의 MemberCoupon을 미사용으로 되돌린다.
     * 코드 없음/공개형/미보유면 무시(no-op). "취소했는데 쿠폰 날림"을 막는다.
     */
    @Transactional
    public void release(Long memberId, String code) {
        couponService.findByCodeOptional(code)
                .filter(Coupon::isIssued)
                .flatMap(coupon -> memberCouponRepository.findByMemberIdAndCouponId(memberId, coupon.getId()))
                .ifPresent(MemberCoupon::release);
    }

    /** 그 회원이 보유한 미사용 쿠폰인지 확인 — 아니면 400. */
    private MemberCoupon requireOwnedUnused(Long memberId, Coupon coupon) {
        MemberCoupon mc = memberCouponRepository.findByMemberIdAndCouponId(memberId, coupon.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                        "발급받지 않은 쿠폰입니다. (쿠폰함을 확인해 주세요)"));
        if (!mc.isUnused()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "이미 사용한 쿠폰입니다.");
        }
        return mc;
    }
}
