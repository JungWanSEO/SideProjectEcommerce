package com.commerce.api.dashboard.service;

import com.commerce.api.dashboard.dto.DashboardResponse;
import com.commerce.api.dashboard.dto.DashboardResponse.DailyRevenue;
import com.commerce.api.dashboard.dto.DashboardResponse.Kpi;
import com.commerce.api.dashboard.dto.DashboardResponse.OrderStatusCount;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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

    private final OrderRepository orderRepository;
    private final SettlementRepository settlementRepository;
    private final MemberRepository memberRepository;
    private final ProductRepository productRepository;

    /** 대시보드 한 화면(KPI + 주문 상태 분포 + 최근 days일 매출 추이). */
    public DashboardResponse getDashboard(int days) {
        int range = Math.min(Math.max(days, MIN_DAYS), MAX_DAYS);

        Kpi kpi = new Kpi(
                orderRepository.count(),
                orderRepository.sumPayableAmountByStatusIn(OrderStatus.PURCHASED),
                settlementRepository.sumNetAmountByStatus(SettlementStatus.SCHEDULED),
                memberRepository.count(),
                productRepository.countByStatus(ProductStatus.ON_SALE));

        return new DashboardResponse(kpi, orderStatusDistribution(), revenueTrend(range));
    }

    /** 모든 OrderStatus를 enum 순서로 — 한 건도 없는 상태는 0으로 채워 분포가 늘 같은 모양이게. */
    private List<OrderStatusCount> orderStatusDistribution() {
        Map<OrderStatus, Long> counts = orderRepository.countGroupByStatus().stream()
                .collect(Collectors.toMap(r -> (OrderStatus) r[0], r -> (Long) r[1]));
        return Arrays.stream(OrderStatus.values())
                .map(s -> new OrderStatusCount(s, counts.getOrDefault(s, 0L)))
                .toList();
    }

    /** 최근 range일 일별 매출 — 기간 내 PURCHASED 주문을 자바에서 일자별 합산 후 빈 날 0 채움. */
    private List<DailyRevenue> revenueTrend(int range) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(range - 1L);
        Map<LocalDate, Long> byDay = orderRepository
                .findAmountsByStatusInSince(OrderStatus.PURCHASED, start.atStartOfDay()).stream()
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
