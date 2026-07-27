package com.commerce.api.settlement.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.settlement.dto.PayoutCreateRequest;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.entity.Payout;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.repository.PayoutRepository;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지급 묶음(Payout) 서비스 — 셀러 정산 항목을 기간으로 묶어 한 번에 지급한다.
 *
 * <p>per-entry 입금처리({@code SettlementService.payout})와 공존한다. 한 항목이 두 경로로 중복
 * 지급되지 않도록, 묶음은 <b>SCHEDULED·미지급(payoutId null)</b> 항목만 묶고(묶이면 payoutId 설정),
 * per-entry 쪽은 payoutId가 있으면 거부한다(SettlementService.payout 가드).
 */
@Service
@RequiredArgsConstructor
public class PayoutService {

    private final PayoutRepository payoutRepository;
    private final SettlementRepository settlementRepository;
    private final SellerRepository sellerRepository;   // sellerName enrich

    /** 지급 묶음 생성 — 셀러의 SCHEDULED·미지급 항목을 기간으로 묶는다. 대상 없으면 400. */
    @Transactional
    public PayoutResponse create(PayoutCreateRequest request) {
        List<SettlementEntry> entries = settlementRepository
                .findBySellerIdAndStatusAndPayoutIdIsNullAndSettledDateBetween(
                        request.sellerId(), SettlementStatus.SCHEDULED, request.from(), request.to());
        if (entries.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "지급할 정산 항목이 없습니다.");
        }

        long gross = 0, fee = 0, platformFee = 0, net = 0;
        for (SettlementEntry e : entries) {
            gross += e.getGrossAmount();
            fee += e.getFee();
            platformFee += e.getPlatformFee();
            net += e.getNetAmount();
        }
        // 음수 순액 가드(#3 P5) — 반품 역분개로 이 기간 net이 음수면 "음수 송금"이 되므로 지급을 만들지 않는다.
        //   음수 SCHEDULED 항목은 payoutId=null로 남아 다음 정산 기간(넓은 범위)에서 양수와 상계·이월된다.
        if (net < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "정산 순액이 음수입니다(환불이 매출을 초과). 다음 정산 기간에 이월 상계됩니다.");
        }
        Payout payout = payoutRepository.save(Payout.create(
                request.sellerId(), request.from(), request.to(), gross, fee, platformFee, net, entries.size()));

        // 묶음 편입은 원자적 조건부 UPDATE로(동시성) — 조건절 없는 setter(dirty checking)는 같은 셀러·윈도우로 동시
        //   create()가 겹치면 같은 항목을 두 묶음이 각각 잡아(lost update) 동일 엔트리 net을 이중지급할 수 있었다.
        //   payout_id IS NULL인 대상만 잡고, 편입된 수가 요청 항목 수와 다르면 경합에 밀린 것이므로 롤백(payout 저장도 취소).
        List<Long> ids = entries.stream().map(SettlementEntry::getId).toList();
        int claimed = settlementRepository.claimForPayout(payout.getId(), ids);
        if (claimed != entries.size()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "정산 항목이 동시에 다른 지급 요청에 편입되었습니다. 잠시 후 다시 시도해 주세요.");
        }

        return PayoutResponse.from(payout, sellerNameOf(request.sellerId()));
    }

    /** 지급 완료 처리 — 묶음 PAID + 묶인 항목들 PAID_OUT. 없으면 404, 이미 지급이면 409. */
    @Transactional
    public PayoutResponse pay(Long id) {
        Payout payout = payoutRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "지급 묶음을 찾을 수 없습니다."));
        payout.markPaid();
        settlementRepository.findByPayoutId(id).forEach(SettlementEntry::markPaidOut);
        return PayoutResponse.from(payout, sellerNameOf(payout.getSellerId()));
    }

    /** 지급 묶음 목록(셀러·상태 필터). sellerName은 enrich. */
    @Transactional(readOnly = true)
    public PageResponse<PayoutResponse> getPayouts(Long sellerId, PayoutStatus status, Pageable pageable) {
        Page<Payout> page;
        if (sellerId == null && status == null) {
            page = payoutRepository.findAll(pageable);
        } else if (status == null) {
            page = payoutRepository.findBySellerId(sellerId, pageable);
        } else if (sellerId == null) {
            page = payoutRepository.findByStatus(status, pageable);
        } else {
            page = payoutRepository.findBySellerIdAndStatus(sellerId, status, pageable);
        }
        Map<Long, String> names = sellerRepository.findAllById(
                        page.getContent().stream().map(Payout::getSellerId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Seller::getId, Seller::getName));
        return PageResponse.from(page.map(p -> PayoutResponse.from(p, names.get(p.getSellerId()))));
    }

    private String sellerNameOf(Long sellerId) {
        return sellerRepository.findById(sellerId).map(Seller::getName).orElse(null);
    }
}
