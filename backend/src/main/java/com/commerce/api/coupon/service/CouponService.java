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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 비즈니스 로직.
 *
 * <p>여러 행에 걸친 규칙(코드 유일성)·발급/조회는 여기서, 한 쿠폰의 사용 가능 여부·할인 계산은
 * {@link Coupon} 엔티티가 책임진다. 체크아웃에서의 적용은 {@link #applyCoupon}을 주문 도메인이 호출하고
 * 결과를 DTO({@link CouponApplyResult})로만 받아 도메인 경계를 지킨다.
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
                request.sellerId(), request.validFrom(), request.validUntil());
        return CouponResponse.from(couponRepository.save(coupon));
    }

    /** 전체 쿠폰 목록(ADMIN, 최신순). */
    public List<CouponResponse> getCoupons() {
        return couponRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(CouponResponse::from)
                .toList();
    }

    /**
     * 체크아웃에서 쿠폰을 적용해 할인 결과를 계산한다(주문 도메인이 호출).
     *
     * <p>적용 대상 금액 = 플랫폼 와이드(sellerId=null) 쿠폰이면 주문 총액, 셀러 한정 쿠폰이면
     * 그 셀러 상품 소계 합({@code grossBySeller}). 코드 없음/기간 외/최소금액 미달/적용대상 없음이면
     * 엔티티 검증이 400을 던진다.
     *
     * @param rawCode       고객이 입력한 코드(대문자 정규화 전)
     * @param orderTotal    주문 총액(할인 전 gross)
     * @param grossBySeller 셀러ID별 소계 합(미귀속 항목은 null 키) — 셀러 한정 쿠폰의 적용 대상 산정용
     */
    public CouponApplyResult applyCoupon(String rawCode, long orderTotal, Map<Long, Long> grossBySeller) {
        String code = normalize(rawCode);
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "존재하지 않는 쿠폰 코드입니다."));

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
