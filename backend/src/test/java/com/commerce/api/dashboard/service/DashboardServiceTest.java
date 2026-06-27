package com.commerce.api.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.dashboard.dto.DashboardResponse;
import com.commerce.api.dashboard.dto.DashboardResponse.DailyRevenue;
import com.commerce.api.dashboard.dto.DashboardResponse.OrderStatusCount;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.time.LocalDate;
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
    @Autowired private MemberRepository memberRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SettlementRepository settlementRepository;

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

        // 주문 5건: PURCHASED(PAID 20000 + SHIPPING 3000 + DELIVERED 5000) + PENDING 9999 + CANCELLED 7000
        orderRepository.save(paidOrder(20000L));
        orderRepository.save(shippingOrder(3000L));
        orderRepository.save(deliveredOrder(5000L));
        orderRepository.save(pendingOrder(9999L));
        orderRepository.save(cancelledOrder(7000L));
        long knownPaidRevenue = 20000L + 3000L + 5000L;   // = 28000 (PURCHASED만)

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
        assertThat(after.kpi().paidRevenue()).isEqualTo(before.kpi().paidRevenue() + knownPaidRevenue);
        assertThat(after.kpi().pendingSettlement()).isEqualTo(before.kpi().pendingSettlement() + 8750L);
        assertThat(after.kpi().memberCount()).isEqualTo(before.kpi().memberCount() + 2);
        assertThat(after.kpi().activeProductCount()).isEqualTo(before.kpi().activeProductCount() + 2); // ON_SALE만

        // 상태 분포: 항상 모든 OrderStatus를 enum 순서로, 각 상태 +1
        assertThat(after.orderStatusDistribution()).extracting(OrderStatusCount::status)
                .containsExactly(OrderStatus.values());
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(countOf(after, s)).isEqualTo(countOf(before, s) + 1);
        }

        // 매출 추이: 7일치 연속, 마지막=오늘, 오늘 버킷이 knownPaidRevenue만큼 증가
        assertThat(after.revenueTrend()).hasSize(7);
        DailyRevenue lastBefore = before.revenueTrend().get(6);
        DailyRevenue lastAfter = after.revenueTrend().get(6);
        assertThat(lastAfter.date()).isEqualTo(LocalDate.now());
        assertThat(lastAfter.revenue()).isEqualTo(lastBefore.revenue() + knownPaidRevenue);
    }

    @Test
    @DisplayName("getDashboard - days는 1~90으로 클램프된다(추이 길이)")
    void getDashboard_clampsDays() {
        assertThat(dashboardService.getDashboard(0).revenueTrend()).hasSize(1);    // 하한
        assertThat(dashboardService.getDashboard(500).revenueTrend()).hasSize(90); // 상한
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

    private long countOf(DashboardResponse r, OrderStatus status) {
        return r.orderStatusDistribution().stream()
                .filter(c -> c.status() == status).findFirst().orElseThrow().count();
    }
}
