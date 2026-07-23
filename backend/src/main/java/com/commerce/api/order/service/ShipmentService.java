package com.commerce.api.order.service;

import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 배송(shipment) 전진 오케스트레이션(#1 P3) — 셀러/ADMIN이 자기 shipment를 forward-only로 전진하고
 * 주문 status를 rollup으로 재계산한다. 인가(셀러 소유권·ADMIN)는 호출자(P5)가 담당한다.
 *
 * <p>같은 주문의 서로 다른 shipment를 동시에 전진하면 부모 주문 낙관 버전 강제 증가가 충돌을 일으키므로,
 * {@link OrderService#pay}·{@code create}와 동일하게 낙관락 충돌 시 최대 3회 새 트랜잭션으로 재시도한다.
 */
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentTransitionWorker worker;

    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public OrderResponse advance(Long shipmentId, ShipmentStatus next, Long changedBy,
            String courier, String trackingNumber) {
        return worker.advance(shipmentId, next, changedBy, courier, trackingNumber);
    }
}
