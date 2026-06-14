package com.commerce.api.seller.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.service.PayoutService;
import com.commerce.api.settlement.service.SettlementService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 콘솔 서비스 — 로그인한 셀러 운영자가 <b>자기 셀러의</b> 정보·정산만 보도록 스코핑한다.
 *
 * <p>회원(Member.sellerId)으로 셀러를 도출해 기존 정산 서비스를 그대로 재사용한다 — 셀러는
 * 자신의 sellerId만 조회하므로 남의 정산은 구조적으로 볼 수 없다(IDOR 차단). 셀러 계정이 아니면 403.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerConsoleService {

    private final MemberRepository memberRepository;
    private final SellerService sellerService;
    private final SettlementService settlementService;
    private final PayoutService payoutService;

    /** 내 셀러 정보. */
    public SellerResponse getMySeller(Long memberId) {
        return sellerService.getSeller(requireSellerId(memberId));
    }

    /** 내 정산 항목(상태·기간 필터). */
    public PageResponse<SettlementResponse> getMySettlements(
            Long memberId, SettlementStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        Long sellerId = requireSellerId(memberId);
        return settlementService.getSettlements(
                new SettlementSearchCondition(sellerId, status, from, to), pageable);
    }

    /** 내 정산서(셀러별 집계 — 본인 1건). */
    public List<SellerSettlementSummary> getMySummary(
            Long memberId, SettlementStatus status, LocalDate from, LocalDate to) {
        Long sellerId = requireSellerId(memberId);
        return settlementService.getSellerSummary(
                new SettlementSearchCondition(sellerId, status, from, to));
    }

    /** 내 지급 묶음 목록(상태 필터). */
    public PageResponse<PayoutResponse> getMyPayouts(Long memberId, PayoutStatus status, Pageable pageable) {
        Long sellerId = requireSellerId(memberId);
        return payoutService.getPayouts(sellerId, status, pageable);
    }

    /** 로그인 회원의 셀러 ID — 셀러 계정(sellerId 보유)이 아니면 403. */
    private Long requireSellerId(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."));
        Long sellerId = member.getSellerId();
        if (sellerId == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "셀러 계정이 아닙니다.");
        }
        return sellerId;
    }
}
