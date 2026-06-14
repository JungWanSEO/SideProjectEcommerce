package com.commerce.api.settlement.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse.OrderItemResponse;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.dto.PaymentResponse;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.service.PaymentService;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.dto.SettlementRunResponse;
import com.commerce.api.settlement.dto.SettlementRunResponse.ProviderBreakdown;
import com.commerce.api.settlement.dto.SettlementRunResponse.SellerBreakdown;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 서비스 — 셀러별 정산(Phase 2).
 *
 * <p>결제(payment)·주문(order)은 상위 도메인이고 정산은 그것을 읽어 가공하는 하위 도메인이다 —
 * 의존 방향은 settlement → payment/order 한 방향(역방향이면 순환). 그래서 결제·주문 데이터는
 * 각 서비스를 통해 DTO로만 받는다(엔티티를 가로질러 만지지 않는다 = 도메인 경계 유지).
 *
 * <p><b>셀러별 정산:</b> 한 결제를 주문 항목의 셀러별로 쪼개 (결제×셀러) 단위 정산 항목을 만든다.
 * 결제의 PG 수수료는 셀러 매출 비례로 <b>안분</b>하고, 플랫폼 판매수수료는 셀러 요율로 따로 뗀다.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final PaymentService paymentService;
    private final PaymentGatewayRouter paymentGatewayRouter;   // PG 수수료율 단일 출처(settlement → payment 정방향)
    private final OrderService orderService;                   // 주문 항목(셀러·소계) 조회(settlement → order)
    private final SellerRepository sellerRepository;           // 셀러 플랫폼 수수료율(commissionRate) 조회

    /**
     * 정산 배치 — PAID 결제 중 아직 정산되지 않은 건을 셀러별로 분해해 SettlementEntry(SCHEDULED)를 만든다.
     *
     * <p>한 결제 → 주문 항목을 셀러별로 묶어 매출(gross)을 합산하고, 셀러마다 항목 1개를 만든다:
     * <ul>
     *   <li><b>PG 수수료(fee)</b>: 결제 전체 PG수수료를 셀러 매출 비례로 안분(반올림 잔차는 매출 최대 셀러에 몰아 합 보존).</li>
     *   <li><b>플랫폼 수수료(platformFee)</b>: 셀러 매출 × 셀러 요율(Seller.commissionRate). 미귀속(sellerId=null)은 0.</li>
     *   <li><b>실수령(netAmount)</b>: gross - fee - platformFee.</li>
     * </ul>
     *
     * <p>멱등성: 같은 결제를 두 번 잡지 않도록 {@code existsByPaymentId}로 거른다(한 결제의 셀러 항목들은
     * 한 트랜잭션에서 함께 생성되므로, 하나라도 있으면 그 결제는 이미 정산된 것).
     */
    @Transactional
    public SettlementRunResponse run() {
        LocalDate settledDate = LocalDate.now().plusDays(SettlementPolicy.PAYOUT_DELAY_DAYS);

        int created = 0;
        long totalGross = 0, totalFee = 0, totalPlatformFee = 0, totalNet = 0;
        // PG별/셀러별 누적 — 삽입 순서 유지(LinkedHashMap)로 응답 순서가 결제·셀러 등장 순서를 따른다.
        Map<String, ProviderAccumulator> byProvider = new LinkedHashMap<>();
        Map<Long, SellerAccumulator> bySeller = new LinkedHashMap<>();
        Map<Long, Double> platformRateCache = new HashMap<>();   // 같은 셀러 반복 조회 방지

        for (PaymentResponse payment : paymentService.getPaidPayments()) {
            if (settlementRepository.existsByPaymentId(payment.id())) {
                continue;   // 이미 정산된 결제 → 건너뜀(멱등)
            }
            String provider = payment.provider();
            double feeRate = paymentGatewayRouter.feeRateOf(provider);   // PG 요율(단일 출처)
            long pgFeeTotal = SettlementPolicy.calculateFee(feeRate, payment.amount());

            // 1) 주문 항목을 셀러별 매출로 묶는다(sellerId=null이면 미귀속 버킷). 등장 순서 보존.
            Map<Long, Long> grossBySeller = new LinkedHashMap<>();
            for (OrderItemResponse item : orderService.getOrderItems(payment.orderId())) {
                grossBySeller.merge(item.sellerId(), item.subtotal(), Long::sum);
            }
            long orderGross = grossBySeller.values().stream().mapToLong(Long::longValue).sum();

            // 2) PG 수수료를 셀러 매출 비례로 안분 + 반올림 잔차를 매출 최대 셀러에 몰아 합을 보존.
            Map<Long, Long> pgFeeBySeller = allocatePgFee(grossBySeller, orderGross, pgFeeTotal);

            // 3) 셀러마다 정산 항목 생성.
            for (Map.Entry<Long, Long> e : grossBySeller.entrySet()) {
                Long sellerId = e.getKey();
                long sellerGross = e.getValue();
                long pgFee = pgFeeBySeller.get(sellerId);
                double platformRate = platformRateOf(sellerId, platformRateCache);
                long platformFee = Math.round(sellerGross * platformRate);

                SettlementEntry entry = SettlementEntry.scheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        sellerId, sellerGross, pgFee, feeRate, platformFee, platformRate, settledDate);
                settlementRepository.save(entry);

                created++;
                totalGross += entry.getGrossAmount();
                totalFee += entry.getFee();
                totalPlatformFee += entry.getPlatformFee();
                totalNet += entry.getNetAmount();
                byProvider.computeIfAbsent(provider, p -> new ProviderAccumulator(p, feeRate)).add(entry);
                bySeller.computeIfAbsent(sellerId, SellerAccumulator::new).add(entry);
            }
        }

        List<ProviderBreakdown> providerBreakdown = new ArrayList<>();
        for (ProviderAccumulator acc : byProvider.values()) {
            providerBreakdown.add(acc.toBreakdown());
        }
        List<SellerBreakdown> sellerBreakdown = new ArrayList<>();
        for (SellerAccumulator acc : bySeller.values()) {
            sellerBreakdown.add(acc.toBreakdown());
        }
        return new SettlementRunResponse(created, totalGross, totalFee, totalPlatformFee, totalNet,
                providerBreakdown, sellerBreakdown);
    }

    /**
     * PG 수수료(총액)를 셀러 매출 비례로 안분한다. 원 단위 반올림으로 생기는 잔차(±몇 원)는
     * 매출이 가장 큰 셀러에게 몰아 <b>합계가 정확히 pgFeeTotal과 같도록</b> 보존한다.
     */
    private Map<Long, Long> allocatePgFee(Map<Long, Long> grossBySeller, long orderGross, long pgFeeTotal) {
        Map<Long, Long> result = new LinkedHashMap<>();
        long allocated = 0;
        Long maxSeller = null;
        long maxGross = -1;
        for (Map.Entry<Long, Long> e : grossBySeller.entrySet()) {
            long share = (orderGross == 0) ? 0 : Math.round((double) pgFeeTotal * e.getValue() / orderGross);
            result.put(e.getKey(), share);
            allocated += share;
            if (e.getValue() > maxGross) {
                maxGross = e.getValue();
                maxSeller = e.getKey();
            }
        }
        if (maxSeller != null && allocated != pgFeeTotal) {
            result.merge(maxSeller, pgFeeTotal - allocated, Long::sum);   // 잔차 보정
        }
        return result;
    }

    /** 셀러의 플랫폼 수수료율(commissionRate). 미귀속(null)·없는 셀러는 0(수수료 없음). 같은 셀러는 캐시. */
    private double platformRateOf(Long sellerId, Map<Long, Double> cache) {
        if (sellerId == null) {
            return 0.0;
        }
        return cache.computeIfAbsent(sellerId,
                sid -> sellerRepository.findById(sid).map(Seller::getCommissionRate).orElse(0.0));
    }

    /** PG 한 곳의 정산 누적기 — run() 안에서만 쓰는 가변 집계 도우미. */
    private static final class ProviderAccumulator {
        private final String provider;
        private final double feeRate;
        private int count;
        private long gross, fee, platformFee, net;

        ProviderAccumulator(String provider, double feeRate) {
            this.provider = provider;
            this.feeRate = feeRate;
        }

        void add(SettlementEntry entry) {
            count++;
            gross += entry.getGrossAmount();
            fee += entry.getFee();
            platformFee += entry.getPlatformFee();
            net += entry.getNetAmount();
        }

        ProviderBreakdown toBreakdown() {
            return new ProviderBreakdown(provider, feeRate, count, gross, fee, platformFee, net);
        }
    }

    /** 셀러 한 곳의 정산 누적기 — run() 안에서만 쓰는 가변 집계 도우미. */
    private static final class SellerAccumulator {
        private final Long sellerId;
        private int count;
        private long gross, fee, platformFee, net;

        SellerAccumulator(Long sellerId) {
            this.sellerId = sellerId;
        }

        void add(SettlementEntry entry) {
            count++;
            gross += entry.getGrossAmount();
            fee += entry.getFee();
            platformFee += entry.getPlatformFee();
            net += entry.getNetAmount();
        }

        SellerBreakdown toBreakdown() {
            return new SellerBreakdown(sellerId, count, gross, fee, platformFee, net);
        }
    }

    /** 정산 항목 목록(페이지) — 셀러·상태·기간 필터(없으면 전체). 최신순(id desc). */
    @Transactional(readOnly = true)
    public PageResponse<SettlementResponse> getSettlements(SettlementSearchCondition condition, Pageable pageable) {
        return PageResponse.from(
                settlementRepository.search(condition, pageable).map(SettlementResponse::from));
    }

    /**
     * 셀러 정산서 — 조건 범위 안에서 셀러별로 매출/수수료/실수령을 집계한다.
     * 집계(QueryDSL group-by)는 sellerId만 알므로, sellerName은 여기서 enrich한다(ID 참조 원칙).
     */
    @Transactional(readOnly = true)
    public List<SellerSettlementSummary> getSellerSummary(SettlementSearchCondition condition) {
        List<SellerSettlementSummary> rows = settlementRepository.summarizeBySeller(condition);
        List<Long> sellerIds = rows.stream()
                .map(SellerSettlementSummary::sellerId).filter(Objects::nonNull).toList();
        Map<Long, String> names = sellerRepository.findAllById(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getName));
        return rows.stream()
                .map(r -> new SellerSettlementSummary(
                        r.sellerId(),
                        r.sellerId() == null ? null : names.get(r.sellerId()),
                        r.count(), r.grossAmount(), r.fee(), r.platformFee(), r.netAmount()))
                .toList();
    }

    /** 입금 확인 처리 → PAID_OUT. (실무라면 은행 입금 대사 후 호출. 여기선 수동 트리거.) */
    @Transactional
    public SettlementResponse payout(Long id) {
        SettlementEntry entry = settlementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "정산 항목을 찾을 수 없습니다."));
        entry.markPaidOut();   // 상태머신 가드 — 이미 PAID_OUT이면 409. 변경은 더티 체킹으로 반영.
        return SettlementResponse.from(entry);
    }
}
