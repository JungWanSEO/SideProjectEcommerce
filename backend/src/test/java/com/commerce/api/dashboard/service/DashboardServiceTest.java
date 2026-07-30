package com.commerce.api.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.dashboard.dto.CancelReasonStatsResponse;
import com.commerce.api.dashboard.dto.CancelReasonStatsResponse.FaultCount;
import com.commerce.api.dashboard.dto.CancelReasonStatsResponse.ReasonCount;
import com.commerce.api.dashboard.dto.DashboardResponse;
import com.commerce.api.dashboard.dto.DashboardResponse.DailyRevenue;
import com.commerce.api.dashboard.dto.DashboardResponse.OrderStatusCount;
import com.commerce.api.dashboard.dto.LowStockResponse;
import com.commerce.api.product.dto.LowStockOption;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.global.common.CancelReason;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.repository.ReturnRequestRepository;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 대시보드 집계 서비스 테스트.
 *
 * <p>@Transactional 롤백이라 기존(시드·다른 테스트 커밋) 데이터를 지우지 않고 그 위에 얹는다 → 절대 카운트가 아니라
 * <b>baseline 대비 증가분(델타)</b>으로 단언해 오염에 견디게 한다. (auto-flush로 같은 tx의 INSERT가 집계 쿼리에 보인다.)
 * 일자 zero-fill 경계는 DB 없이 {@link DashboardService#fillDailySeries} 순수 함수로 따로 검증.
 */
@SpringBootTest
@Transactional
class DashboardServiceTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private ReturnRequestRepository returnRequestRepository;

    private static final AtomicLong SEQ = new AtomicLong();

    @Test
    @DisplayName("getDashboard - KPI·상태분포·매출추이가 추가 데이터만큼 증가한다(델타)")
    void getDashboard_aggregatesDelta() {
        DashboardResponse before = dashboardService.getDashboard(7);

        // 회원 2명, 상품 ON_SALE 2 + DISCONTINUED 1
        memberRepository.save(member());
        memberRepository.save(member());
        productRepository.save(product(ProductStatus.ON_SALE));
        productRepository.save(product(ProductStatus.ON_SALE));
        productRepository.save(product(ProductStatus.DISCONTINUED));

        // 주문 5건: 상태 분포용(각 상태 +1). 매출은 이제 결제(Payment) 기준이라 주문 금액과 무관.
        orderRepository.save(paidOrder(20000L));
        orderRepository.save(shippingOrder(3000L));
        orderRepository.save(deliveredOrder(5000L));
        orderRepository.save(pendingOrder(9999L));
        orderRepository.save(cancelledOrder(7000L));

        // 순매출 = PAID 결제의 amount − refundedAmount. 부분환불은 net에 반영, 전액환불(CANCELLED)은 제외.
        paidPayment(20000L, 0L);        // net 20000
        paidPayment(3000L, 0L);         // net 3000
        paidPayment(5000L, 0L);         // net 5000
        paidPayment(10000L, 4000L);     // 부분환불 → net 6000
        paidPayment(8000L, 8000L);      // 전액환불 → CANCELLED → 제외(net 0)
        long knownNetRevenue = 20000L + 3000L + 5000L + 6000L;   // = 34000

        // 정산: SCHEDULED net 8750 (대기) + PAID_OUT net (대기 아님)
        settlementRepository.save(SettlementEntry.scheduled(
                seq(), seq(), "tx-sch-" + seq(), "TOSS", 1L, 10000L, 250L, 0.025, 1000L, 0.10, LocalDate.now()));
        SettlementEntry paidOut = SettlementEntry.scheduled(
                seq(), seq(), "tx-out-" + seq(), "TOSS", 1L, 20000L, 500L, 0.025, 2000L, 0.10, LocalDate.now());
        paidOut.markPaidOut();
        settlementRepository.save(paidOut);

        DashboardResponse after = dashboardService.getDashboard(7);

        // KPI 델타
        assertThat(after.kpi().totalOrders()).isEqualTo(before.kpi().totalOrders() + 5);
        assertThat(after.kpi().netRevenue()).isEqualTo(before.kpi().netRevenue() + knownNetRevenue);
        assertThat(after.kpi().pendingSettlement()).isEqualTo(before.kpi().pendingSettlement() + 8750L);
        assertThat(after.kpi().memberCount()).isEqualTo(before.kpi().memberCount() + 2);
        assertThat(after.kpi().activeProductCount()).isEqualTo(before.kpi().activeProductCount() + 2); // ON_SALE만

        // 상태 분포: 항상 모든 OrderStatus를 enum 순서로, 각 상태 +1
        assertThat(after.orderStatusDistribution()).extracting(OrderStatusCount::status)
                .containsExactly(OrderStatus.values());
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(countOf(after, s)).isEqualTo(countOf(before, s) + 1);
        }

        // 순매출 추이: 7일치 연속, 마지막=오늘, 오늘 버킷이 knownNetRevenue만큼 증가(결제일=오늘)
        assertThat(after.revenueTrend()).hasSize(7);
        DailyRevenue lastBefore = before.revenueTrend().get(6);
        DailyRevenue lastAfter = after.revenueTrend().get(6);
        assertThat(lastAfter.date()).isEqualTo(LocalDate.now());
        assertThat(lastAfter.revenue()).isEqualTo(lastBefore.revenue() + knownNetRevenue);
    }

    @Test
    @DisplayName("getDashboard - days는 1~90으로 클램프된다(추이 길이)")
    void getDashboard_clampsDays() {
        assertThat(dashboardService.getDashboard(0).revenueTrend()).hasSize(1);    // 하한
        assertThat(dashboardService.getDashboard(500).revenueTrend()).hasSize(90); // 상한
    }

    @Test
    @DisplayName("getLowStock - 품절/임박 건수가 추가한 옵션만큼 늘고(델타), 목록은 재고 적은 순")
    void getLowStock_countsAndOrder() {
        LowStockResponse before = dashboardService.getLowStock(5, 10);

        // 판매중 상품: 품절(0) 2개 + 임박(3) 1개 + 여유(50) 1개
        Product onSale = product(ProductStatus.ON_SALE);
        onSale.addOption(ProductOption.create("S", 0));
        onSale.addOption(ProductOption.create("M", 0));
        onSale.addOption(ProductOption.create("L", 3));
        onSale.addOption(ProductOption.create("XL", 50));
        productRepository.save(onSale);

        // 판매중지 상품의 품절 옵션은 리포트 대상이 아니다(재입고할 이유가 없음)
        Product discontinued = product(ProductStatus.DISCONTINUED);
        discontinued.addOption(ProductOption.create("FREE", 0));
        productRepository.save(discontinued);

        LowStockResponse after = dashboardService.getLowStock(5, 10);

        assertThat(after.soldOutCount()).isEqualTo(before.soldOutCount() + 2);    // 판매중지 것은 안 셈
        assertThat(after.lowStockCount()).isEqualTo(before.lowStockCount() + 1);  // 재고 3만 임박(50은 여유)
        assertThat(after.items()).isSortedAccordingTo(Comparator.comparingInt(LowStockOption::stock));
        assertThat(after.items()).allMatch(i -> i.stock() <= 5);
        assertThat(after.items()).noneMatch(i -> i.productStatus() == ProductStatus.DISCONTINUED);
    }

    @Test
    @DisplayName("getLowStock - threshold는 0~100, limit은 1~100으로 클램프된다")
    void getLowStock_clamps() {
        assertThat(dashboardService.getLowStock(-1, 10).threshold()).isZero();     // 하한(품절만)
        assertThat(dashboardService.getLowStock(999, 10).threshold()).isEqualTo(100);  // 상한
        assertThat(dashboardService.getLowStock(5, 0).items()).hasSizeLessThanOrEqualTo(1);    // limit 하한 1
        assertThat(dashboardService.getLowStock(5, 999).items()).hasSizeLessThanOrEqualTo(100); // limit 상한
    }

    @Test
    @DisplayName("fillDailySeries - [start,end] 모든 날짜를 채우고 없는 날은 0(순수 함수)")
    void fillDailySeries_zeroFills() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 5);   // 5일
        Map<LocalDate, Long> byDay = Map.of(
                LocalDate.of(2026, 6, 1), 1000L,
                LocalDate.of(2026, 6, 3), 3000L);

        var series = DashboardService.fillDailySeries(byDay, start, end);

        assertThat(series).hasSize(5);
        assertThat(series).extracting(DailyRevenue::date)
                .containsExactly(start, start.plusDays(1), start.plusDays(2), start.plusDays(3), end);
        assertThat(series).extracting(DailyRevenue::revenue)
                .containsExactly(1000L, 0L, 3000L, 0L, 0L);   // 빈 날(6/2·6/4·6/5)은 0
    }

    // === 헬퍼 ===

    private long seq() {
        return SEQ.incrementAndGet();
    }

    private Member member() {
        return Member.builder()
                .email("dash-" + seq() + "@commerce.com").password("ENCODED")
                .nickname("dash").role(Role.USER).build();
    }

    @Test
    @DisplayName("getCancelReasonStats - 취소·반품 사유를 한 축으로 합치고 귀책으로 접는다(미기록은 분리)")
    void getCancelReasonStats_mergesCancelAndReturnByReason() {
        CancelReasonStatsResponse before = dashboardService.getCancelReasonStats();

        // 취소 2건: 변심(고객 귀책) + 불량(셀러 귀책) / 사유 미기록 1건
        orderRepository.save(cancelledOrder(CancelReason.CHANGE_OF_MIND));
        orderRepository.save(cancelledOrder(CancelReason.DEFECTIVE));
        orderRepository.save(cancelledOrder(null));
        // 반품 1건: 같은 변심 사유 → 사유 축에서 취소분과 합산돼야 한다
        returnRequestRepository.save(returnRequest(CancelReason.CHANGE_OF_MIND));

        CancelReasonStatsResponse after = dashboardService.getCancelReasonStats();

        assertThat(after.totalCancelledItems()).isEqualTo(before.totalCancelledItems() + 3);
        assertThat(after.totalReturns()).isEqualTo(before.totalReturns() + 1);
        assertThat(after.unrecordedCancels()).isEqualTo(before.unrecordedCancels() + 1);   // 사유 없는 취소는 따로

        ReasonCount mind = reasonOf(after, "CHANGE_OF_MIND");
        ReasonCount mindBefore = reasonOf(before, "CHANGE_OF_MIND");
        assertThat(mind.cancelCount()).isEqualTo(mindBefore.cancelCount() + 1);
        assertThat(mind.returnCount()).isEqualTo(mindBefore.returnCount() + 1);            // 두 소스 합산
        assertThat(mind.total()).isEqualTo(mind.cancelCount() + mind.returnCount());
        assertThat(mind.fault()).isEqualTo("CUSTOMER");                                    // 사유 enum의 귀책 메타

        // 귀책 접기: 셀러 귀책(불량)이 1 늘었다
        assertThat(faultTotal(after, "SELLER")).isEqualTo(faultTotal(before, "SELLER") + 1);
        // 건수 0인 사유는 목록에 넣지 않는다(표를 늘리기만 한다)
        assertThat(after.byReason()).allSatisfy(r -> assertThat(r.total()).isPositive());
        // 사유별 합계 + 미기록 = 전체(둘을 합쳐야 전체가 되는 관계를 화면이 설명할 수 있어야 한다)
        long reasonSum = after.byReason().stream().mapToLong(ReasonCount::total).sum();
        assertThat(reasonSum + after.unrecordedCancels() + after.unrecordedReturns())
                .isEqualTo(after.totalCancelledItems() + after.totalReturns());
    }

    private ReasonCount reasonOf(CancelReasonStatsResponse stats, String reason) {
        return stats.byReason().stream().filter(r -> r.reason().equals(reason)).findFirst()
                .orElse(new ReasonCount(reason, "NONE", 0, 0, 0));
    }

    private long faultTotal(CancelReasonStatsResponse stats, String fault) {
        return stats.byFault().stream().filter(f -> f.fault().equals(fault)).findFirst()
                .map(FaultCount::total).orElse(0L);
    }

    /** 항목 1개를 사유와 함께 취소한 주문(전체 취소). 사유 null이면 "미기록" 케이스. */
    private Order cancelledOrder(CancelReason reason) {
        Order o = paidOrder(10_000L);
        o.cancel(100L, "테스트 취소", reason);
        return o;
    }

    /** 반품 요청 1건(사유 코드만 의미 있음 — 집계는 상태와 무관). */
    private ReturnRequest returnRequest(CancelReason reasonCode) {
        long seq = SEQ.incrementAndGet();
        return ReturnRequest.create(seq, seq, seq, 1L, 100L, ReturnType.RETURN, "사유", reasonCode, 1, null);
    }

    private Product product(ProductStatus status) {
        return Product.builder().name("대시상품").price(1000L).description("d").status(status).build();
    }

    private Order order(long unitPrice) {
        Order o = Order.create(100L);
        o.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).productName("상품").size("M")
                .orderPrice(unitPrice).quantity(1).build());
        return o;
    }

    private Order paidOrder(long amount) {
        Order o = order(amount);
        o.markPaid();
        return o;
    }

    private Order shippingOrder(long amount) {
        Order o = paidOrder(amount);
        o.advanceShipping(OrderStatus.SHIPPING);
        return o;
    }

    private Order deliveredOrder(long amount) {
        Order o = shippingOrder(amount);
        o.advanceShipping(OrderStatus.DELIVERED);
        return o;
    }

    private Order pendingOrder(long amount) {
        return order(amount);   // 생성 직후 PENDING
    }

    private Order cancelledOrder(long amount) {
        Order o = order(amount);
        o.cancel();
        return o;
    }

    /** PAID 결제 한 건 저장(refunded>0이면 그만큼 부분/전액 환불 — 전액이면 CANCELLED로 빠진다). */
    private void paidPayment(long amount, long refunded) {
        Payment p = Payment.ready(1L, amount, "MOCK", "TOSS", "dash-pay-" + seq());
        p.markPaid("tx-" + seq());
        if (refunded > 0) {
            p.partialRefund(refunded);
        }
        paymentRepository.save(p);
    }

    private long countOf(DashboardResponse r, OrderStatus status) {
        return r.orderStatusDistribution().stream()
                .filter(c -> c.status() == status).findFirst().orElseThrow().count();
    }
}
