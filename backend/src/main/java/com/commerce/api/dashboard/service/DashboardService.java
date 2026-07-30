package com.commerce.api.dashboard.service;

import com.commerce.api.dashboard.dto.CancelReasonStatsResponse;
import com.commerce.api.dashboard.dto.CancelReasonStatsResponse.FaultCount;
import com.commerce.api.dashboard.dto.CancelReasonStatsResponse.ReasonCount;
import com.commerce.api.dashboard.dto.DashboardResponse;
import com.commerce.api.dashboard.dto.DashboardResponse.DailyRevenue;
import com.commerce.api.dashboard.dto.DashboardResponse.Kpi;
import com.commerce.api.dashboard.dto.DashboardResponse.OrderStatusCount;
import com.commerce.api.dashboard.dto.LowStockResponse;
import com.commerce.api.global.common.CancelReason;
import com.commerce.api.global.config.CacheConfig;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.returns.repository.ReturnRequestRepository;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 대시보드 집계 서비스 — 여러 도메인 저장소를 <b>읽기만</b> 해 한 화면을 채운다(read-model).
 *
 * <p>정산 서비스가 이미 주문·결제를 가로질러 읽는 것과 같은 결의 단방향 의존(dashboard → 각 도메인 repo).
 * 일자 그룹핑은 H2(테스트)/MySQL(운영) 날짜함수 차이를 피해 <b>자바 스트림</b>으로 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    /** 매출 추이 기간 한도(일). 과한 범위 조회를 막는 가드. */
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    /** 재고 임박 기준·목록 크기 한도(가드). */
    private static final int MIN_THRESHOLD = 0;      // 0이면 "품절만"
    private static final int MAX_THRESHOLD = 100;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    /** 재입고 대상 상품 상태 — 판매중지는 채울 이유가 없어 리포트에서 제외한다. */
    private static final List<ProductStatus> RESTOCKABLE =
            List.of(ProductStatus.ON_SALE, ProductStatus.SOLD_OUT);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;   // 순매출(환불 차감) KPI·추이 — #9
    private final SettlementRepository settlementRepository;
    private final MemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final ReturnRequestRepository returnRequestRepository;   // 반품 사유 집계(#8 후속)

    /** 대시보드 한 화면(KPI + 주문 상태 분포 + 최근 days일 매출 추이). 비싼 집계라 30초 캐시(준실시간·days별). */
    @Cacheable(value = CacheConfig.DASHBOARD, key = "#days")
    public DashboardResponse getDashboard(int days) {
        int range = Math.min(Math.max(days, MIN_DAYS), MAX_DAYS);

        Kpi kpi = new Kpi(
                orderRepository.count(),
                // 순매출(환불 차감) — 주문 gross(totalPrice−discount)는 부분취소분까지 세 결제·정산 net과 어긋난다.
                //   PAID 결제의 amount−refundedAmount 합이 실제 받은 순매출과 일치(전액 환불은 CANCELLED로 제외).
                paymentRepository.sumNetRevenueByStatus(PaymentStatus.PAID),
                settlementRepository.sumNetAmountByStatus(SettlementStatus.SCHEDULED),
                memberRepository.count(),
                productRepository.countByStatus(ProductStatus.ON_SALE));

        return new DashboardResponse(kpi, orderStatusDistribution(), revenueTrend(range));
    }

    /**
     * 재고 임박·품절 리포트 — 재고가 threshold 이하인 <b>옵션</b>(사이즈=SKU)을 재고 적은 순으로.
     *
     * <p><b>캐시하지 않는다</b>(대시보드 집계와 다른 결정): 재고는 주문마다 줄어드는 값이라 30초 전 스냅샷을 보고
     * "아직 여유 있네" 하면 품절을 방치하게 된다. 리포트의 목적 자체가 "지금 채워야 할 것"이므로 항상 최신 조회.
     *
     * <p>대상은 판매중·품절 상품뿐 — 판매중지(DISCONTINUED)는 재입고할 이유가 없다.
     */
    public LowStockResponse getLowStock(int threshold, int limit) {
        int bound = Math.min(Math.max(threshold, MIN_THRESHOLD), MAX_THRESHOLD);
        int size = Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);

        return new LowStockResponse(
                bound,
                productRepository.countOptionsWithStockBetween(RESTOCKABLE, 0, 0),          // 품절
                productRepository.countOptionsWithStockBetween(RESTOCKABLE, 1, bound),      // 임박(1~bound)
                productRepository.findLowStockOptions(RESTOCKABLE, bound, size));
    }

    /**
     * 취소·반품 사유 집계(#8 후속) — 취소(order_item)와 반품(return_request)의 사유를 <b>한 축으로 합친다</b>.
     *
     * <p>사유가 enum으로 구조화돼 있어 group-by 두 번이면 끝난다(자유텍스트였다면 셀 수 없었다). 귀책(fault)은
     * 사유가 들고 있는 메타를 접어 만들고, <b>사유 미기록</b> 건수는 따로 노출해 "사유별 합계 &lt; 전체"가
     * 오류로 보이지 않게 한다(사유는 add-only·nullable로 도입됐다).
     *
     * <p>기간 필터는 두지 않았다 — 취소 시각을 별도로 보관하지 않아(항목 updatedAt은 교환 스왑 등에도 갱신됨)
     * "언제 취소됐는지"의 기준을 새로 정해야 하고, 그건 별도 결정이라 전체 기간 집계로 시작한다.
     * 캐시도 하지 않는다(호출 빈도가 낮고 운영 판단에 최신값이 낫다).
     */
    public CancelReasonStatsResponse getCancelReasonStats() {
        Map<CancelReason, Long> cancels = toReasonMap(orderRepository.countCancelledItemsByReason());
        Map<CancelReason, Long> returns = toReasonMap(returnRequestRepository.countByReasonCode());

        long unrecordedCancels = cancels.getOrDefault(null, 0L);
        long unrecordedReturns = returns.getOrDefault(null, 0L);

        List<ReasonCount> byReason = Arrays.stream(CancelReason.values())
                .map(reason -> new ReasonCount(
                        reason.name(),
                        reason.getFault().name(),
                        cancels.getOrDefault(reason, 0L),
                        returns.getOrDefault(reason, 0L),
                        cancels.getOrDefault(reason, 0L) + returns.getOrDefault(reason, 0L)))
                .filter(r -> r.total() > 0)   // 0건 사유는 표를 늘리기만 한다(상태 분포와 달리 고정 축이 아님)
                .sorted(Comparator.comparingLong(ReasonCount::total).reversed())
                .toList();

        List<FaultCount> byFault = byReason.stream()
                .collect(Collectors.groupingBy(ReasonCount::fault, Collectors.summingLong(ReasonCount::total)))
                .entrySet().stream()
                .map(e -> new FaultCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(FaultCount::total).reversed())
                .toList();

        return new CancelReasonStatsResponse(
                sum(cancels), sum(returns), unrecordedCancels, unrecordedReturns, byReason, byFault);
    }

    /** [reason, count] 행을 맵으로 — 사유 미기록(null) 키도 그대로 담는다(HashMap은 null 키 허용). */
    private static Map<CancelReason, Long> toReasonMap(List<Object[]> rows) {
        Map<CancelReason, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((CancelReason) row[0], (Long) row[1]);
        }
        return map;
    }

    private static long sum(Map<CancelReason, Long> map) {
        return map.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 모든 OrderStatus를 enum 순서로 — 한 건도 없는 상태는 0으로 채워 분포가 늘 같은 모양이게. */
    private List<OrderStatusCount> orderStatusDistribution() {
        Map<OrderStatus, Long> counts = orderRepository.countGroupByStatus().stream()
                .collect(Collectors.toMap(r -> (OrderStatus) r[0], r -> (Long) r[1]));
        return Arrays.stream(OrderStatus.values())
                .map(s -> new OrderStatusCount(s, counts.getOrDefault(s, 0L)))
                .toList();
    }

    /** 최근 range일 일별 순매출 — 기간 내 PAID 결제의 순매출(amount−refunded)을 결제일별로 합산 후 빈 날 0 채움. */
    private List<DailyRevenue> revenueTrend(int range) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(range - 1L);
        Map<LocalDate, Long> byDay = paymentRepository
                .findNetAmountsByStatusSince(PaymentStatus.PAID, start.atStartOfDay()).stream()
                .collect(Collectors.groupingBy(
                        r -> ((LocalDateTime) r[0]).toLocalDate(),
                        Collectors.summingLong(r -> (Long) r[1])));
        return fillDailySeries(byDay, start, today);
    }

    /**
     * 일자→합계 맵을 [start, end] 모든 날짜로 펼쳐 연속 시계열을 만든다(없는 날은 0).
     * DB·시각과 무관한 순수 함수라 단위 테스트로 경계(빈 날 채움·구간 길이)를 검증한다.
     */
    static List<DailyRevenue> fillDailySeries(Map<LocalDate, Long> byDay, LocalDate start, LocalDate end) {
        List<DailyRevenue> series = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            series.add(new DailyRevenue(d, byDay.getOrDefault(d, 0L)));
        }
        return series;
    }
}
