package com.commerce.api.order.entity;

import java.util.Set;

/**
 * 주문 상태.
 *
 * <p>배송 진행은 <b>forward-only</b>: PAID → SHIPPING → DELIVERED (건너뛰기·되돌리기 금지).
 * 전이 규칙은 {@link Order#advanceShipping(OrderStatus)}가 강제한다. CANCELLED는 결제/배송 흐름과 별개의
 * 종료 상태 — 배송 시작 전(PENDING/PAID)까지만 취소 가능하고 SHIPPING/DELIVERED는 취소할 수 없다.
 */
public enum OrderStatus {
    PENDING,    // 결제 대기 (주문 생성됨, 재고 미차감)
    PAID,       // 결제 완료 (재고 차감됨)
    SHIPPING,   // 배송 중 (ADMIN이 PAID → SHIPPING으로 전진)
    DELIVERED,  // 배송 완료 (ADMIN이 SHIPPING → DELIVERED로 전진, 종료)
    CANCELLED;  // 취소 (배송 시작 전까지만 가능)

    /**
     * "구매 완료"로 보는 상태 집합(결제됨 이후 · 취소 제외)의 <b>단일 출처</b>.
     * 리뷰 자격("구매자만 작성")·추천 구매 신호가 이 집합을 기준으로 한다 — forward-only라
     * 배송 중/완료 주문도 분명한 구매이므로 PAID만이 아니라 SHIPPING·DELIVERED까지 포함한다.
     */
    public static final Set<OrderStatus> PURCHASED = Set.of(PAID, SHIPPING, DELIVERED);
}