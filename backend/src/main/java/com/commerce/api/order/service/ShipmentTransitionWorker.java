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
 * shipment 배송 상태 전진의 <b>트랜잭션 단위</b>(#1 P3/P5) — {@link ShipmentService}(@Retryable)가 감싼다.
 * OrderProcessor(주문 생성/결제)·OrderExpiryWorker(만료)와 같은 "재시도 오케스트레이터 + @Transactional 워커" 분리.
 *
 * <p>동시성: 부모 주문을 {@link OrderRepository#findByIdForShipmentRollup 낙관 버전 강제 증가}로 잡아, 같은 주문의
 * 서로 다른 shipment를 동시에 전진하면 반드시 orders.version 충돌을 일으킨다 → 늦은 tx는 @Retryable로 <b>새 트랜잭션</b>
 * (fresh 1차 캐시)에서 재실행해 형제 shipment의 커밋된 상태로 rollup을 정확히 재계산한다.
 *
 * <p>인가는 <b>이 트랜잭션 안에서</b> 확인한다(TOCTOU 회피) — 셀러 소유권/ADMIN 주문 매칭을 검증한 뒤 전진한다.
 */
@Component
@RequiredArgsConstructor
public class ShipmentTransitionWorker {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;

    /** 인가 확인 없이 전진(내부/동시성 테스트용). */
    @Transactional
    public OrderResponse advance(Long shipmentId, ShipmentStatus next, Long changedBy,
            String courier, String trackingNumber) {
        Loaded l = load(shipmentId);
        return apply(l, next, changedBy, courier, trackingNumber);
    }

    /** 셀러 전진 — 그 shipment가 이 셀러 것일 때만(아니거나 플랫폼 null 버킷이면 403). */
    @Transactional
    public OrderResponse advanceForSeller(Long shipmentId, Long sellerId, ShipmentStatus next,
            Long changedBy, String courier, String trackingNumber) {
        Loaded l = load(shipmentId);
        if (!l.shipment.belongsToSeller(sellerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인 셀러의 배송만 처리할 수 있습니다.");
        }
        return apply(l, next, changedBy, courier, trackingNumber);
    }

    /** ADMIN 전진(플랫폼 null 버킷 포함) — 지정 주문의 shipment가 맞을 때만(경로 불일치면 404). */
    @Transactional
    public OrderResponse advanceForAdmin(Long orderId, Long shipmentId, ShipmentStatus next,
            Long changedBy, String courier, String trackingNumber) {
        Loaded l = load(shipmentId);
        if (!l.order.getId().equals(orderId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "해당 주문의 배송 건이 아닙니다.");
        }
        return apply(l, next, changedBy, courier, trackingNumber);
    }

    /**
     * shipment 엔티티를 먼저 로딩하지 않고 orderId만 스칼라로 → 부모 주문을 강제증가 락으로 잡고, 그 안에서 shipment를
     * fresh로 읽는다(1차 캐시 stale 회피 + 형제 커밋 반영).
     */
    private Loaded load(Long shipmentId) {
        Long orderId = shipmentRepository.findOrderIdById(shipmentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "배송 건을 찾을 수 없습니다."));
        Order order = orderRepository.findByIdForShipmentRollup(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
        Shipment shipment = order.getShipments().stream()
                .filter(s -> s.getId().equals(shipmentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "배송 건을 찾을 수 없습니다."));
        return new Loaded(order, shipment);
    }

    private OrderResponse apply(Loaded l, ShipmentStatus next, Long changedBy, String courier, String trackingNumber) {
        l.shipment.advanceShipping(next, changedBy, courier, trackingNumber);
        l.order.recomputeStatusFromShipments(changedBy, null);   // 주문 status는 shipment rollup 파생
        return OrderResponse.from(l.order);
    }

    private record Loaded(Order order, Shipment shipment) {
    }
}
