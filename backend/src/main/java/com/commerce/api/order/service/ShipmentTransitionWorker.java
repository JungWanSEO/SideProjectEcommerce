package com.commerce.api.order.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * shipment 배송 상태 전진의 <b>트랜잭션 단위</b>(#1 P3) — {@link ShipmentService}(@Retryable)가 감싼다.
 * OrderProcessor(주문 생성/결제)·OrderExpiryWorker(만료)와 같은 "재시도 오케스트레이터 + @Transactional 워커" 분리.
 *
 * <p>동시성: 부모 주문을 {@link OrderRepository#findByIdForShipmentRollup 낙관 버전 강제 증가}로 잡아, 같은 주문의
 * 서로 다른 shipment를 동시에 전진하면 반드시 orders.version 충돌을 일으킨다 → 늦은 tx는 @Retryable로 <b>새 트랜잭션</b>
 * (fresh 1차 캐시)에서 재실행해 형제 shipment의 커밋된 상태로 rollup을 정확히 재계산한다.
 */
@Component
@RequiredArgsConstructor
public class ShipmentTransitionWorker {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse advance(Long shipmentId, ShipmentStatus next, Long changedBy,
            String courier, String trackingNumber) {
        // shipment 엔티티를 먼저 로딩하지 않고 orderId만 스칼라로 → 락 이후 형제 shipment를 fresh로 읽는다(1차 캐시 stale 회피).
        Long orderId = shipmentRepository.findOrderIdById(shipmentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "배송 건을 찾을 수 없습니다."));
        Order order = orderRepository.findByIdForShipmentRollup(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));

        Shipment shipment = order.getShipments().stream()
                .filter(s -> s.getId().equals(shipmentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "배송 건을 찾을 수 없습니다."));

        shipment.advanceShipping(next, changedBy, courier, trackingNumber);
        order.recomputeStatusFromShipments(changedBy, null);   // 주문 status는 shipment rollup 파생
        return OrderResponse.from(order);
    }
}
