package com.commerce.api.settlement.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderDiscountInfo;
import com.commerce.api.order.dto.OrderResponse.OrderItemResponse;
import com.commerce.api.order.entity.OrderItemStatus;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.dto.PaymentResponse;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.service.PaymentService;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.dto.SettlementReverseResponse;
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
        long totalGross = 0, totalFee = 0, totalPlatformFee = 0, totalNet = 0, totalDiscount = 0;
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

            // 1) 주문 항목을 셀러별 매출(gross, 할인 전)로 묶는다(sellerId=null이면 미귀속 버킷). 등장 순서 보존.
            //    부분환불로 취소(CANCELLED)된 항목은 제외 — 환불분은 정산하지 않는다.
            Map<Long, Long> grossBySeller = new LinkedHashMap<>();
            for (OrderItemResponse item : orderService.getOrderItems(payment.orderId())) {
                if (item.status() != OrderItemStatus.ACTIVE) {
                    continue;
                }
                grossBySeller.merge(item.sellerId(), item.subtotal(), Long::sum);
            }
            long orderGross = grossBySeller.values().stream().mapToLong(Long::longValue).sum();

            // 2) 쿠폰 할인을 셀러별로 안분: 셀러 한정이면 그 셀러에, 플랫폼 와이드면 매출 비례로.
            OrderDiscountInfo discount = orderService.getOrderDiscount(payment.orderId());
            Map<Long, Long> discountBySeller = allocateDiscount(grossBySeller, orderGross, discount);

            // 3) 할인 후 셀러 몫(reduced gross). Σreduced = payable(고객 실제 결제액) → 대사 group-by-sum이 그대로 MATCHED.
            Map<Long, Long> reducedGrossBySeller = new LinkedHashMap<>();
            grossBySeller.forEach((sid, g) -> reducedGrossBySeller.put(sid, g - discountBySeller.getOrDefault(sid, 0L)));
            long payable = reducedGrossBySeller.values().stream().mapToLong(Long::longValue).sum();

            // 4) PG 수수료는 실제 처리액(payable=할인 후) 기준으로 떼고 셀러 몫 비례로 안분(잔차는 최대 셀러).
            long pgFeeTotal = SettlementPolicy.calculateFee(feeRate, payable);
            Map<Long, Long> pgFeeBySeller = proRate(reducedGrossBySeller, payable, pgFeeTotal);

            // 5) 셀러마다 정산 항목 생성(gross=할인 후 몫, 플랫폼 부담이면 net에 할인 환원).
            for (Map.Entry<Long, Long> e : reducedGrossBySeller.entrySet()) {
                Long sellerId = e.getKey();
                long sellerGross = e.getValue();
                long pgFee = pgFeeBySeller.get(sellerId);
                double platformRate = platformRateOf(sellerId, platformRateCache);
                long platformFee = Math.round(sellerGross * platformRate);
                long sellerDiscount = discountBySeller.getOrDefault(sellerId, 0L);
                String fundedBy = sellerDiscount > 0 ? discount.fundedBy() : null;

                SettlementEntry entry = SettlementEntry.scheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        sellerId, sellerGross, pgFee, feeRate, platformFee, platformRate,
                        sellerDiscount, fundedBy, settledDate);
                settlementRepository.save(entry);

                created++;
                totalGross += entry.getGrossAmount();
                totalFee += entry.getFee();
                totalPlatformFee += entry.getPlatformFee();
                totalNet += entry.getNetAmount();
                totalDiscount += entry.getDiscountAmount();
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
        return new SettlementRunResponse(created, totalGross, totalFee, totalPlatformFee, totalNet, totalDiscount,
                providerBreakdown, sellerBreakdown);
    }

    /**
     * 환불 상계(역분개) 배치 — 이미 정산된 결제에서 항목이 부분환불(CANCELLED)됐으면, 줄어든 만큼
     * <b>음수 정산 항목</b>을 만들어 셀러 정산을 상계한다(원장 일관·감사 추적).
     *
     * <p>방식(멱등): 정산된 각 결제에 대해 현재 <b>활성 항목 기준 목표(target)</b>를 다시 계산하고,
     * 기존 정산 합계(셀러별)와의 차이(target − 기존)를 역분개 항목으로 남긴다. 차이가 0이면 만들지 않으므로
     * 여러 번 돌려도 안전하다(상계 후 합계 = target → 다음 실행은 차이 0).
     */
    @Transactional
    public SettlementReverseResponse reverseRefunds() {
        LocalDate settledDate = LocalDate.now().plusDays(SettlementPolicy.PAYOUT_DELAY_DAYS);
        int reversed = 0;
        long totalReversedNet = 0;

        for (PaymentResponse payment : paymentService.getPaidPayments()) {
            List<SettlementEntry> existing = settlementRepository.findByPaymentId(payment.id());
            if (existing.isEmpty()) {
                continue;   // 아직 정산 안 된 결제는 run()의 몫
            }
            // 할인이 적용된 주문의 환불 상계는 할인 재안분(남은 항목 기준)이 필요 → Step 2b로 분리. 여기선 건너뛴다.
            if (existing.stream().anyMatch(e -> e.getDiscountAmount() != 0)) {
                continue;
            }
            String provider = payment.provider();
            double feeRate = paymentGatewayRouter.feeRateOf(provider);

            // 현재 활성 항목 기준 목표(셀러별 gross·PG수수료 안분)
            Map<Long, Long> grossBySeller = new LinkedHashMap<>();
            for (OrderItemResponse item : orderService.getOrderItems(payment.orderId())) {
                if (item.status() != OrderItemStatus.ACTIVE) {
                    continue;
                }
                grossBySeller.merge(item.sellerId(), item.subtotal(), Long::sum);
            }
            long orderGross = grossBySeller.values().stream().mapToLong(Long::longValue).sum();
            long pgFeeTotal = SettlementPolicy.calculateFee(feeRate, orderGross);
            Map<Long, Long> pgFeeBySeller = proRate(grossBySeller, orderGross, pgFeeTotal);

            // 기존 정산 합계(셀러별) + 적용된 플랫폼 요율(역분개에 그대로 사용 — 그때 요율 보존)
            Map<Long, long[]> settledBySeller = new LinkedHashMap<>();   // [gross, fee, platformFee]
            Map<Long, Double> platformRateBySeller = new HashMap<>();
            for (SettlementEntry e : existing) {
                long[] agg = settledBySeller.computeIfAbsent(e.getSellerId(), k -> new long[3]);
                agg[0] += e.getGrossAmount();
                agg[1] += e.getFee();
                agg[2] += e.getPlatformFee();
                platformRateBySeller.putIfAbsent(e.getSellerId(), e.getPlatformFeeRate());
            }

            // 셀러별 차이 → 역분개. (취소로 줄었으면 음수 항목)
            for (Map.Entry<Long, long[]> se : settledBySeller.entrySet()) {
                Long sellerId = se.getKey();
                long[] settled = se.getValue();
                long targetGross = grossBySeller.getOrDefault(sellerId, 0L);
                double platformRate = platformRateBySeller.getOrDefault(sellerId, 0.0);
                long dGross = targetGross - settled[0];
                long dFee = pgFeeBySeller.getOrDefault(sellerId, 0L) - settled[1];
                long dPlatformFee = Math.round(targetGross * platformRate) - settled[2];
                if (dGross == 0 && dFee == 0 && dPlatformFee == 0) {
                    continue;   // 변화 없음
                }
                SettlementEntry rev = SettlementEntry.scheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        sellerId, dGross, dFee, feeRate, dPlatformFee, platformRate, settledDate);
                settlementRepository.save(rev);
                reversed++;
                totalReversedNet += rev.getNetAmount();
            }
        }
        return new SettlementReverseResponse(reversed, totalReversedNet);
    }

    /**
     * 쿠폰 할인을 셀러별로 안분한다.
     * <ul>
     *   <li><b>셀러 한정 쿠폰</b>(sellerId 있음): 그 셀러에 전액(그 셀러 매출 한도 내). 다른 셀러는 0.</li>
     *   <li><b>플랫폼 와이드 쿠폰</b>(sellerId=null): 주문 전체 대상 → 셀러 매출 비례로 안분(PG수수료와 같은 패턴).</li>
     * </ul>
     * 할인이 없거나 적용 대상이 없으면 빈 맵.
     */
    private Map<Long, Long> allocateDiscount(Map<Long, Long> grossBySeller, long orderGross,
            OrderDiscountInfo discount) {
        // null 키(미귀속 셀러)도 조회하므로 null 허용 맵을 쓴다(Map.of는 null 키 조회 시 NPE).
        long total = Math.min(discount.discountAmount(), orderGross);   // 가드: 주문 매출을 넘겨 깎지 않는다
        if (total <= 0) {
            return new LinkedHashMap<>();
        }
        if (discount.sellerId() != null) {
            long cap = Math.min(total, grossBySeller.getOrDefault(discount.sellerId(), 0L));
            Map<Long, Long> result = new LinkedHashMap<>();
            if (cap > 0) {
                result.put(discount.sellerId(), cap);
            }
            return result;
        }
        return proRate(grossBySeller, orderGross, total);
    }

    /**
     * 총액(total)을 키별 비중(amountsByKey 값 / base) 비례로 안분한다. 원 단위 반올림으로 생기는 잔차(±몇 원)는
     * 비중이 가장 큰 키에 몰아 <b>합계가 정확히 total과 같도록</b> 보존한다. (PG수수료·플랫폼 와이드 할인 공용)
     */
    private Map<Long, Long> proRate(Map<Long, Long> amountsByKey, long base, long total) {
        Map<Long, Long> result = new LinkedHashMap<>();
        long allocated = 0;
        Long maxKey = null;
        long maxAmount = -1;
        for (Map.Entry<Long, Long> e : amountsByKey.entrySet()) {
            long share = (base == 0) ? 0 : Math.round((double) total * e.getValue() / base);
            result.put(e.getKey(), share);
            allocated += share;
            if (e.getValue() > maxAmount) {
                maxAmount = e.getValue();
                maxKey = e.getKey();
            }
        }
        if (maxKey != null && allocated != total) {
            result.merge(maxKey, total - allocated, Long::sum);   // 잔차 보정
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
        private long gross, fee, platformFee, net, discount;

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
            discount += entry.getDiscountAmount();
        }

        ProviderBreakdown toBreakdown() {
            return new ProviderBreakdown(provider, feeRate, count, gross, fee, platformFee, net, discount);
        }
    }

    /** 셀러 한 곳의 정산 누적기 — run() 안에서만 쓰는 가변 집계 도우미. */
    private static final class SellerAccumulator {
        private final Long sellerId;
        private int count;
        private long gross, fee, platformFee, net, discount;

        SellerAccumulator(Long sellerId) {
            this.sellerId = sellerId;
        }

        void add(SettlementEntry entry) {
            count++;
            gross += entry.getGrossAmount();
            fee += entry.getFee();
            platformFee += entry.getPlatformFee();
            net += entry.getNetAmount();
            discount += entry.getDiscountAmount();
        }

        SellerBreakdown toBreakdown() {
            return new SellerBreakdown(sellerId, count, gross, fee, platformFee, net, discount);
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
                        r.count(), r.grossAmount(), r.fee(), r.platformFee(), r.discountAmount(), r.netAmount()))
                .toList();
    }

    /**
     * per-entry 입금 확인 처리 → PAID_OUT. (실무라면 은행 입금 대사 후 호출. 여기선 수동 트리거.)
     * 지급 묶음(Payout)에 편입된 항목은 묶음으로 지급해야 하므로 거부한다(중복 지급 방지).
     */
    @Transactional
    public SettlementResponse payout(Long id) {
        SettlementEntry entry = settlementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "정산 항목을 찾을 수 없습니다."));
        if (entry.getPayoutId() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "지급 묶음에 포함된 항목입니다. 묶음으로 지급하세요.");
        }
        entry.markPaidOut();   // 상태머신 가드 — 이미 PAID_OUT이면 409. 변경은 더티 체킹으로 반영.
        return SettlementResponse.from(entry);
    }
}
