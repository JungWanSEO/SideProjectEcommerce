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
        Payout payout = payoutRepository.save(Payout.create(
                request.sellerId(), request.from(), request.to(), gross, fee, platformFee, net, entries.size()));
        entries.forEach(e -> e.assignPayout(payout.getId()));   // 묶음에 편입(영속 → dirty checking)

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
