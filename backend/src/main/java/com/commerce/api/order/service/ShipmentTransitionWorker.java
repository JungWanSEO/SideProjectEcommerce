package com.commerce.api.order.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.dto.SellerShipmentResponse;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * shipment 배송 상태 전진의 <b>트랜잭션 단위</b>(#1 P3/P5) — {@link ShipmentService}가 감싼다.
 * OrderProcessor(주문 생성/결제)·OrderExpiryWorker(만료)와 같은 "오케스트레이터 + @Transactional 워커" 분리.
 *
 * <p>동시성(리뷰 교정): 부모 주문을 {@link OrderRepository#findByIdForUpdate 비관적 쓰기 락}으로 잡는다 —
 * 취소·ADMIN 일괄 전진 등 <b>다른 상태 변경 경로도 같은 락</b>을 써서, 같은 주문의 서로 다른 자식(shipment/항목)을
 * 동시에 바꿔도 늦은 tx가 로드 시점에 막혀 앞 tx 커밋 후 형제의 최신 상태로 rollup을 정확히 재계산한다.
 * READ_COMMITTED로 락 획득 이후 형제 shipment를 fresh로 읽는다(스냅샷 고정 회피 — H2/MySQL 공통 정합).
 *
 * <p>인가는 <b>이 트랜잭션 안에서</b> 확인한다(TOCTOU 회피) — 셀러 소유권/ADMIN 주문 매칭을 검증한 뒤 전진한다.
 */
@Component
@RequiredArgsConstructor
public class ShipmentTransitionWorker {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;

    /** 인가 확인 없이 전진(내부/동시성 테스트용). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse advance(Long shipmentId, ShipmentStatus next, Long changedBy,
            String courier, String trackingNumber) {
        Loaded l = load(shipmentId);
        return apply(l, next, changedBy, courier, trackingNumber);
    }

    /**
     * 셀러 전진 — 그 shipment가 이 셀러 것일 때만(아니거나 플랫폼 null 버킷이면 403). 응답은 <b>셀러 스코프</b>
     * ({@link SellerShipmentResponse})로, 타 셀러 항목·구매자 식별자·주문 총액을 노출하지 않는다(리뷰 #5 교정).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SellerShipmentResponse advanceForSeller(Long shipmentId, Long sellerId, ShipmentStatus next,
            Long changedBy, String courier, String trackingNumber) {
        Loaded l = load(shipmentId);
        if (!l.shipment.belongsToSeller(sellerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "본인 셀러의 배송만 처리할 수 있습니다.");
        }
        applyTransition(l, next, changedBy, courier, trackingNumber);
        return SellerShipmentResponse.of(l.order, l.shipment);
    }

    /** ADMIN 전진(플랫폼 null 버킷 포함) — 지정 주문의 shipment가 맞을 때만(경로 불일치면 404). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse advanceForAdmin(Long orderId, Long shipmentId, ShipmentStatus next,
            Long changedBy, String courier, String trackingNumber) {
        Loaded l = load(shipmentId);
        if (!l.order.getId().equals(orderId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "해당 주문의 배송 건이 아닙니다.");
        }
        return apply(l, next, changedBy, courier, trackingNumber);
    }

    /**
     * shipment 엔티티를 먼저 로딩하지 않고 orderId만 스칼라로 → 부모 주문을 <b>비관적 쓰기 락</b>으로 잡고, 그 안에서
     * shipment를 fresh로 읽는다(락 이전에 엔티티를 캐싱하지 않아 형제의 커밋된 상태를 정확히 반영).
     */
    private Loaded load(Long shipmentId) {
        Long orderId = shipmentRepository.findOrderIdById(shipmentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "배송 건을 찾을 수 없습니다."));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
        Shipment shipment = order.getShipments().stream()
                .filter(s -> s.getId().equals(shipmentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "배송 건을 찾을 수 없습니다."));
        return new Loaded(order, shipment);
    }

    /** ADMIN/내부 경로 — 전이 후 주문 전체 응답(운영자는 전체를 봐도 되므로). */
    private OrderResponse apply(Loaded l, ShipmentStatus next, Long changedBy, String courier, String trackingNumber) {
        applyTransition(l, next, changedBy, courier, trackingNumber);
        return OrderResponse.from(l.order);
    }

    /** 전이 + 주문 status rollup 재계산(파생). 응답 형태는 호출자가 결정. */
    private void applyTransition(Loaded l, ShipmentStatus next, Long changedBy, String courier, String trackingNumber) {
        l.shipment.advanceShipping(next, changedBy, courier, trackingNumber);
        l.order.recomputeStatusFromShipments(changedBy, null);   // 주문 status는 shipment rollup 파생
    }

    private record Loaded(Order order, Shipment shipment) {
    }
}
