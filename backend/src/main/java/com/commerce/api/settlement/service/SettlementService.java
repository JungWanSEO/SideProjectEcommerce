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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final com.commerce.api.returns.service.ReturnQueryService returnQueryService;   // 셀러 귀책 회수비(settlement → returns)

    /**
     * 정방향 정산이 만드는 <b>매출 원장</b> 종류(#8 후속). 멱등 게이트가 이 둘만 본다 —
     * 회수비·귀책 과금은 반품 확정 시점에 생겨 정방향보다 먼저 존재할 수 있기 때문이다.
     */
    private static final java.util.List<com.commerce.api.settlement.entity.SettlementEntryKind> SALE_LEDGER_KINDS =
            java.util.List.of(com.commerce.api.settlement.entity.SettlementEntryKind.SALE,
                    com.commerce.api.settlement.entity.SettlementEntryKind.SHIPPING);

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
            // 멱등 게이트는 <b>매출 원장(SALE/SHIPPING)</b>만 본다(#8 후속). 회수비·귀책 과금 엔트리는 반품
            // 확정 시점에 생겨 정방향 정산보다 먼저 존재할 수 있는데, 결제 단위로만 보면 그런 결제가
            // "이미 정산됨"으로 오판돼 영원히 미정산(=셀러 매출 전액 소실)이 된다.
            if (settlementRepository.existsByPaymentIdAndEntryKindIn(payment.id(), SALE_LEDGER_KINDS)) {
                continue;   // 이미 정산된 결제 → 건너뜀(멱등)
            }
            String provider = payment.provider();
            double feeRate = paymentGatewayRouter.feeRateOf(provider);   // PG 요율(단일 출처)

            // 1) 활성 항목을 셀러별 매출(gross, 할인 전)·항목별 안분 할인(discountShare)으로 묶는다(취소분 제외).
            //    할인은 주문이 항목별로 안분해 두므로 정산은 활성 항목의 share만 더하면 된다 → 부분환불돼도
            //    남은 항목의 실효가 합 = 결제액이 되어 대사가 그대로 MATCHED(run/reverseRefunds가 같은 출처를 씀).
            Map<Long, Long> grossBySeller = new LinkedHashMap<>();
            Map<Long, Long> discountBySeller = new LinkedHashMap<>();
            List<OrderItemResponse> orderItems = orderService.getOrderItems(payment.orderId());
            for (OrderItemResponse item : orderItems) {
                if (item.status() != OrderItemStatus.ACTIVE) {
                    continue;
                }
                grossBySeller.merge(item.sellerId(), item.subtotal(), Long::sum);
                discountBySeller.merge(item.sellerId(), item.discountShare(), Long::sum);
            }
            // 배송비 유지 여부: 전량취소(모든 항목 CANCELLED)에서만 환불 → CANCELLED 아닌 항목(ACTIVE/RETURNED)이
            //   하나라도 있으면 유지(#4 적대적리뷰 HIGH — 전량반품을 전량취소로 오인해 배송비 매출을 소실하던 것 교정).
            boolean shippingRetained = orderItems.stream().anyMatch(i -> i.status() != OrderItemStatus.CANCELLED);
            OrderDiscountInfo discount = orderService.getOrderDiscount(payment.orderId());   // 부담 주체(net 환원 판정)

            // 2) 할인 후 셀러 몫(reduced gross). Σreduced = payable(고객 실제 결제액) → 대사 group-by-sum이 그대로 MATCHED.
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

            // 6) 배송비(플랫폼 수익) 엔트리(#4): 배송비가 유지되는 주문이면 sellerId=null·gross=배송비 엔트리 1건을
            //    만들어 Σgross = (PG 잔여 청구액) 으로 복원한다(대사 MATCHED). PG수수료는 배송비 몫도 플랫폼이 부담
            //    (net = 배송비 − 수수료). 전량취소(모든 항목 CANCELLED)면 배송비도 환불됐으므로 엔트리를 안 만든다.
            //    전량반품이면 활성은 0이지만 배송비는 유지되므로(shippingRetained=true) 배송비 엔트리는 만든다.
            long shippingFee = discount.shippingFee();
            if (shippingFee > 0 && shippingRetained) {
                long shippingPgFee = SettlementPolicy.calculateFee(feeRate, shippingFee);
                SettlementEntry shipEntry = SettlementEntry.shippingScheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        shippingFee, shippingPgFee, feeRate, settledDate);
                settlementRepository.save(shipEntry);
                created++;
                totalGross += shipEntry.getGrossAmount();
                totalFee += shipEntry.getFee();
                totalNet += shipEntry.getNetAmount();
                byProvider.computeIfAbsent(provider, p -> new ProviderAccumulator(p, feeRate)).add(shipEntry);
                bySeller.computeIfAbsent(null, SellerAccumulator::new).add(shipEntry);
            }

            // 7) 반품 회수비(플랫폼 수익) 엔트리(#8 후속): 고객 귀책 반품에서 환불을 줄여 플랫폼이 보유한 금액.
            //    PG 잔여에 실재하는 돈이라 배송비와 같은 이유로 gross에 실어 원장 총액을 복원한다.
            //    (정방향 run 시점엔 보통 0이고, 반품이 먼저 일어난 결제에서만 값이 있다.)
            long returnCharge = discount.returnShippingCharge();
            if (returnCharge > 0) {
                long returnPgFee = SettlementPolicy.calculateFee(feeRate, returnCharge);
                SettlementEntry returnEntry = SettlementEntry.returnShippingScheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        returnCharge, returnPgFee, feeRate, settledDate);
                settlementRepository.save(returnEntry);
                created++;
                totalGross += returnEntry.getGrossAmount();
                totalFee += returnEntry.getFee();
                totalNet += returnEntry.getNetAmount();
                byProvider.computeIfAbsent(provider, p -> new ProviderAccumulator(p, feeRate)).add(returnEntry);
                bySeller.computeIfAbsent(null, SellerAccumulator::new).add(returnEntry);
            }

            // 8) 셀러 귀책 과금 엔트리(#8 후속 P4): 셀러 귀책 반품의 회수비를 셀러 정산에서 뗀다.
            //    gross=0·chargeAmount=금액 → net = −금액. gross를 0으로 두는 것이 핵심 — 셀러↔플랫폼 내부
            //    조정액이라 PG 원장에 대응 금액이 없어서, gross에 실으면 대사가 즉시 AMOUNT_MISMATCH로 튄다.
            for (Map.Entry<Long, Long> fc : returnQueryService.getSellerFaultCharges(payment.orderId()).entrySet()) {
                if (fc.getValue() <= 0) {
                    continue;
                }
                SettlementEntry chargeEntry = SettlementEntry.faultCharge(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        fc.getKey(), fc.getValue(), settledDate);
                settlementRepository.save(chargeEntry);
                created++;
                totalNet += chargeEntry.getNetAmount();   // gross·fee는 0이라 총계에 영향 없음(대사 불변)
                byProvider.computeIfAbsent(provider, p -> new ProviderAccumulator(p, feeRate)).add(chargeEntry);
                bySeller.computeIfAbsent(fc.getKey(), SellerAccumulator::new).add(chargeEntry);
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

        // 역분개 후보 = 정산된 모든 결제(PAID + CANCELLED). 반품 전액환불로 CANCELLED된 결제까지 포함해야
        //   역분개가 누락되지 않는다(#3 P5 클로백 누수 fix). 정방향 run()은 계속 PAID만.
        for (PaymentResponse payment : paymentService.getSettlementReversalCandidates()) {
            List<SettlementEntry> existing = settlementRepository.findByPaymentId(payment.id());
            // 진입 게이트도 <b>매출 원장</b> 기준이어야 한다(#8 후속). 회수비/귀책 과금 엔트리만 있고 정방향
            // 정산이 아직 없는 결제에서 이 게이트가 통과되면, 셀러 target − 0 의 <b>양수</b> diff가 생겨
            // 역분개 경로가 정방향 정산을 만들어 버린다(= 이중 지급). run()의 몫으로 넘긴다.
            if (existing.stream().noneMatch(e -> e.getEntryKind().isSaleLedger())) {
                continue;   // 아직 정산 안 된 결제는 run()의 몫
            }
            String provider = payment.provider();
            double feeRate = paymentGatewayRouter.feeRateOf(provider);
            OrderDiscountInfo discount = orderService.getOrderDiscount(payment.orderId());   // 부담 주체(net 환원)

            // 현재 활성 항목 기준 목표 — gross·항목별 안분 할인. reduced=gross−discount, PG수수료는 payable 기준.
            // run()과 같은 항목별 출처를 쓰므로 할인 주문도 자연히 일관(미환불이면 target=settled → diff 0).
            Map<Long, Long> grossBySeller = new LinkedHashMap<>();
            Map<Long, Long> discountBySeller = new LinkedHashMap<>();
            List<OrderItemResponse> orderItems = orderService.getOrderItems(payment.orderId());
            for (OrderItemResponse item : orderItems) {
                if (item.status() != OrderItemStatus.ACTIVE) {
                    continue;
                }
                grossBySeller.merge(item.sellerId(), item.subtotal(), Long::sum);
                discountBySeller.merge(item.sellerId(), item.discountShare(), Long::sum);
            }
            // 배송비 유지 여부(#4): 전량취소(모든 항목 CANCELLED)에서만 환불 → CANCELLED 아닌 항목이 하나라도 있으면 유지.
            //   활성 항목만 보면(과거) 전량반품(RETURNED)을 전량취소로 오인해 배송비를 잘못 상계했다(적대적리뷰 HIGH 교정).
            boolean shippingRetained = orderItems.stream().anyMatch(i -> i.status() != OrderItemStatus.CANCELLED);
            Map<Long, Long> reducedGrossBySeller = new LinkedHashMap<>();
            grossBySeller.forEach((sid, g) -> reducedGrossBySeller.put(sid, g - discountBySeller.getOrDefault(sid, 0L)));
            long payable = reducedGrossBySeller.values().stream().mapToLong(Long::longValue).sum();
            long pgFeeTotal = SettlementPolicy.calculateFee(feeRate, payable);
            Map<Long, Long> pgFeeBySeller = proRate(reducedGrossBySeller, payable, pgFeeTotal);

            // 기존 정산 합계(셀러별) [gross, fee, platformFee, discount] + 적용된 플랫폼 요율 보존.
            //   배송비 엔트리(shipping=true)는 셀러 집계에서 <b>분리</b>한다 — 안 그러면 null 버킷에 섞여
            //   매 실행마다 -배송비로 역분개돼 멱등성이 깨진다(#4). 배송비는 아래에서 따로 상계한다.
            Map<Long, long[]> settledBySeller = new LinkedHashMap<>();
            Map<Long, Double> platformRateBySeller = new HashMap<>();
            for (SettlementEntry e : existing) {
                // 셀러 매출(SALE) 외 종류는 전부 셀러 루프에서 배제한다(#8 후속). 배제하지 않으면
                //   (a) sellerId=null 버킷에 섞여 매 실행 역분개되거나(플랫폼 엔트리)
                //   (b) platformRateBySeller.putIfAbsent가 그 엔트리의 요율 0.0을 먼저 집어
                //       그 셀러의 dPlatformFee가 0으로 계산되는 리스트 순서 의존 버그가 생긴다(귀책 과금).
                // 각 종류는 아래에서 자기 target과만 비교된다.
                if (e.getEntryKind() != com.commerce.api.settlement.entity.SettlementEntryKind.SALE) {
                    continue;
                }
                long[] agg = settledBySeller.computeIfAbsent(e.getSellerId(), k -> new long[4]);
                agg[0] += e.getGrossAmount();
                agg[1] += e.getFee();
                agg[2] += e.getPlatformFee();
                agg[3] += e.getDiscountAmount();
                platformRateBySeller.putIfAbsent(e.getSellerId(), e.getPlatformFeeRate());
            }

            // 셀러별 차이 → 역분개. (취소로 줄었으면 음수 항목)
            for (Map.Entry<Long, long[]> se : settledBySeller.entrySet()) {
                Long sellerId = se.getKey();
                long[] settled = se.getValue();
                double platformRate = platformRateBySeller.getOrDefault(sellerId, 0.0);
                long targetGross = reducedGrossBySeller.getOrDefault(sellerId, 0L);   // 할인 후 몫
                long targetDiscount = discountBySeller.getOrDefault(sellerId, 0L);
                long dGross = targetGross - settled[0];
                long dFee = pgFeeBySeller.getOrDefault(sellerId, 0L) - settled[1];
                long dPlatformFee = Math.round(targetGross * platformRate) - settled[2];
                long dDiscount = targetDiscount - settled[3];
                if (dGross == 0 && dFee == 0 && dPlatformFee == 0 && dDiscount == 0) {
                    continue;   // 변화 없음
                }
                // 역분개의 부담 주체는 원 정산과 동일 → net 환원(subsidy)도 선형으로 상계된다(dNet = targetNet − settledNet).
                String fundedBy = (targetDiscount != 0 || settled[3] != 0) ? discount.fundedBy() : null;
                SettlementEntry rev = SettlementEntry.scheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        sellerId, dGross, dFee, feeRate, dPlatformFee, platformRate, dDiscount, fundedBy, settledDate);
                settlementRepository.save(rev);
                reversed++;
                totalReversedNet += rev.getNetAmount();
            }

            // 배송비 상계(#4): 배송비 엔트리를 따로 모아 목표(배송비 유지면 shippingFee, 전량취소면 0)와의 차이를
            //   역분개한다. 부분취소·부분반품·전량반품은 CANCELLED 아닌 항목이 남아 shippingRetained=true →
            //   targetShip=settled → diff 0(배송비 유지). 전량취소(모든 항목 CANCELLED)면 target 0 → -배송비 상계.
            //   상계 후 Σ배송비 엔트리=0=target이라 재실행에도 멱등.
            long settledShipGross = 0, settledShipFee = 0;
            for (SettlementEntry e : existing) {
                if (e.getEntryKind() == com.commerce.api.settlement.entity.SettlementEntryKind.SHIPPING) {
                    settledShipGross += e.getGrossAmount();
                    settledShipFee += e.getFee();
                }
            }
            long targetShipGross = shippingRetained ? discount.shippingFee() : 0L;
            long targetShipFee = shippingRetained ? SettlementPolicy.calculateFee(feeRate, discount.shippingFee()) : 0L;
            long dShipGross = targetShipGross - settledShipGross;
            long dShipFee = targetShipFee - settledShipFee;
            if (dShipGross != 0 || dShipFee != 0) {
                SettlementEntry shipRev = SettlementEntry.shippingScheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        dShipGross, dShipFee, feeRate, settledDate);
                settlementRepository.save(shipRev);
                reversed++;
                totalReversedNet += shipRev.getNetAmount();
            }

            // 반품 회수비 상계(#8 후속): 자기 target = 주문의 회수비 누계. 누계는 단조 증가라 반품이 새로 생길
            //   때마다 그 차액만 추가되고, 변화가 없으면 diff 0이라 재실행에도 멱등이다.
            //   (이 버킷이 없으면 회수비 엔트리가 어느 target에도 안 걸려 매 실행 통째로 역분개돼 사라진다.)
            long settledReturnGross = 0, settledReturnFee = 0;
            for (SettlementEntry e : existing) {
                if (e.getEntryKind() == com.commerce.api.settlement.entity.SettlementEntryKind.RETURN_SHIPPING) {
                    settledReturnGross += e.getGrossAmount();
                    settledReturnFee += e.getFee();
                }
            }
            long targetReturnGross = discount.returnShippingCharge();
            long targetReturnFee = SettlementPolicy.calculateFee(feeRate, targetReturnGross);
            long dReturnGross = targetReturnGross - settledReturnGross;
            long dReturnFee = targetReturnFee - settledReturnFee;
            if (dReturnGross != 0 || dReturnFee != 0) {
                SettlementEntry returnRev = SettlementEntry.returnShippingScheduled(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        dReturnGross, dReturnFee, feeRate, settledDate);
                settlementRepository.save(returnRev);
                reversed++;
                totalReversedNet += returnRev.getNetAmount();
            }

            // 셀러 귀책 과금 상계(#8 후속 P4): 셀러별로 자기 target(현재 귀책 회수비 합)과 이미 정산된 합의
            //   차이만 남긴다. 셀러 매출 버킷과 분리돼 있어 매출 역분개와 서로를 오염시키지 않는다.
            Map<Long, Long> settledChargeBySeller = new LinkedHashMap<>();
            for (SettlementEntry e : existing) {
                if (e.getEntryKind() == com.commerce.api.settlement.entity.SettlementEntryKind.FAULT_CHARGE) {
                    settledChargeBySeller.merge(e.getSellerId(), e.getChargeAmount(), Long::sum);
                }
            }
            Map<Long, Long> targetChargeBySeller =
                    new LinkedHashMap<>(returnQueryService.getSellerFaultCharges(payment.orderId()));
            Set<Long> chargeSellers = new LinkedHashSet<>(targetChargeBySeller.keySet());
            chargeSellers.addAll(settledChargeBySeller.keySet());
            for (Long sellerId : chargeSellers) {
                long dCharge = targetChargeBySeller.getOrDefault(sellerId, 0L)
                        - settledChargeBySeller.getOrDefault(sellerId, 0L);
                if (dCharge == 0) {
                    continue;
                }
                SettlementEntry chargeRev = SettlementEntry.faultCharge(
                        payment.id(), payment.orderId(), payment.pgTransactionId(), provider,
                        sellerId, dCharge, settledDate);
                settlementRepository.save(chargeRev);
                reversed++;
                totalReversedNet += chargeRev.getNetAmount();
            }
        }
        return new SettlementReverseResponse(reversed, totalReversedNet);
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
