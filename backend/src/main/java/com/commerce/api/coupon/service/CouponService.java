package com.commerce.api.coupon.service;

import com.commerce.api.coupon.dto.CouponApplyResult;
import com.commerce.api.coupon.dto.CouponCreateRequest;
import com.commerce.api.coupon.dto.CouponResponse;
import com.commerce.api.coupon.entity.Coupon;
import com.commerce.api.coupon.repository.CouponRepository;
import com.commerce.api.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 비즈니스 로직.
 *
 * <p>여러 행에 걸친 규칙(코드 유일성)·발급/조회는 여기서, 한 쿠폰의 사용 가능 여부·할인 계산은
 * {@link Coupon} 엔티티가 책임진다. <b>할인 계산기</b> 역할 — 코드 조회({@link #findByCode})와
 * 할인 계산({@link #calculateDiscount})을 제공하고, 발급형 쿠폰의 보유·단일 사용은
 * {@code MemberCouponService}가 이 위에서 오케스트레이션한다(공개형/발급형 분기).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;

    /** 쿠폰 발급(ADMIN). 코드는 대문자 정규화해 저장하고 유일성을 보장한다. */
    @Transactional
    public CouponResponse create(CouponCreateRequest request) {
        String code = normalize(request.code());
        if (couponRepository.existsByCode(code)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 쿠폰 코드입니다.");
        }
        Coupon coupon = Coupon.create(
                code, request.name(), request.discountType(), request.discountValue(),
                request.maxDiscountAmount(), request.minOrderAmount(), request.fundedBy(),
                request.sellerId(), request.issueType(), request.validFrom(), request.validUntil());
        return CouponResponse.from(couponRepository.save(coupon));
    }

    /** 전체 쿠폰 목록(ADMIN, 최신순). */
    public List<CouponResponse> getCoupons() {
        return couponRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(CouponResponse::from)
                .toList();
    }

    /** 코드로 쿠폰을 찾는다(없으면 빈 Optional). 코드는 대문자 정규화해 매칭. */
    public Optional<Coupon> findByCodeOptional(String rawCode) {
        return couponRepository.findByCode(normalize(rawCode));
    }

    /** 코드로 쿠폰을 찾는다(없으면 400). 체크아웃 적용 진입점. */
    public Coupon findByCode(String rawCode) {
        return findByCodeOptional(rawCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "존재하지 않는 쿠폰 코드입니다."));
    }

    /**
     * 쿠폰의 할인 결과를 계산한다(사용/발급 처리는 하지 않는 순수 계산 — MemberCouponService가 오케스트레이션).
     *
     * <p>적용 대상 금액 = 플랫폼 와이드(sellerId=null) 쿠폰이면 주문 총액, 셀러 한정 쿠폰이면
     * 그 셀러 상품 소계 합({@code grossBySeller}). 기간 외/최소금액 미달/적용대상 없음이면 엔티티 검증이 400.
     *
     * @param coupon        대상 쿠폰
     * @param orderTotal    주문 총액(할인 전 gross)
     * @param grossBySeller 셀러ID별 소계 합(미귀속 항목은 null 키) — 셀러 한정 쿠폰의 적용 대상 산정용
     */
    public CouponApplyResult calculateDiscount(Coupon coupon, long orderTotal, Map<Long, Long> grossBySeller) {
        long applicable = (coupon.getSellerId() == null)
                ? orderTotal
                : grossBySeller.getOrDefault(coupon.getSellerId(), 0L);

        coupon.validateUsable(applicable, LocalDateTime.now());
        long discount = coupon.calculateDiscount(applicable);
        return new CouponApplyResult(coupon.getCode(), discount, coupon.getFundedBy(), coupon.getSellerId());
    }

    /** 코드 정규화: 앞뒤 공백 제거 + 대문자(대소문자·여백에 관대하게 매칭). */
    private static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }
}
