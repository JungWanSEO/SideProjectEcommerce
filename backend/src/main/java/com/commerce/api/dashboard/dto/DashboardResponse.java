package com.commerce.api.dashboard.dto;

import com.commerce.api.order.entity.OrderStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 어드민 대시보드 한 화면을 한 번에 채우는 읽기 모델(read-model).
 *
 * <p>여러 도메인(주문·정산·회원·상품)을 가로질러 <b>집계만</b> 한다 — 생성/수정은 각 도메인 서비스 몫.
 * 단일 응답으로 묶어 프론트가 한 번의 호출로 KPI·분포·추이를 모두 받게 한다(BFF 성격).
 */
public record DashboardResponse(
        Kpi kpi,
        List<OrderStatusCount> orderStatusDistribution,
        List<DailyRevenue> revenueTrend
) {
    /** 상단 요약 카드 묶음. 금액은 원(KRW) 정수. */
    public record Kpi(
            long totalOrders,        // 전체 주문 수(모든 상태)
            long netRevenue,         // 순매출(환불 차감) — PAID 결제의 amount−refundedAmount 합, 결제·정산 net과 정합
            long pendingSettlement,  // 정산 대기 금액(SCHEDULED 정산항목 netAmount 합)
            long memberCount,        // 회원 수
            long activeProductCount  // 판매 중(ON_SALE) 상품 수
    ) {}

    /** 주문 상태별 건수 — 모든 OrderStatus를 enum 순서로(없는 상태는 0). */
    public record OrderStatusCount(OrderStatus status, long count) {}

    /** 하루치 매출 한 점 — 빈 날도 0으로 채워 연속된 시계열을 만든다(차트 끊김 방지). */
    public record DailyRevenue(LocalDate date, long revenue) {}
}
