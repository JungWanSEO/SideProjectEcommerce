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
    private final com.commerce.api.order.repository.ShipmentRepository shipmentRepository;

    /**
     * 셀러의 배송 목록(셀러 콘솔 "출고 관리") — 무엇을 언제 보내야 하는지 보는 화면의 데이터.
     *
     * <p>전진 API만 있고 목록이 없어 셀러가 자기 shipmentId를 알 방법이 없었다(주문 목록 DTO엔 배송 정보가 없다).
     * 응답은 전진 응답과 <b>같은 셀러 스코프 DTO</b>를 쓴다 — 자기 셀러 항목만·구매자 식별자 없이 배송지만
     * (P5 리뷰에서 정한 원칙을 목록에도 그대로 적용). 스코프는 쿼리(sellerId)와 전이 시 소유권 검증으로 이중 방어.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.commerce.api.global.common.PageResponse<com.commerce.api.order.dto.SellerShipmentResponse>
            getSellerShipments(Long sellerId, ShipmentStatus status,
                    org.springframework.data.domain.Pageable pageable) {
        return com.commerce.api.global.common.PageResponse.from(
                shipmentRepository.findSellerShipments(sellerId, status, pageable)
                        .map(s -> com.commerce.api.order.dto.SellerShipmentResponse.of(s.getOrder(), s)));
    }

    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public OrderResponse advance(Long shipmentId, ShipmentStatus next, Long changedBy,
            String courier, String trackingNumber) {
        return worker.advance(shipmentId, next, changedBy, courier, trackingNumber);
    }

    /** 셀러가 자기 shipment를 전진(소유권 검증 포함). 응답은 셀러 스코프(타 셀러·구매자 정보 제외, 리뷰 #5). */
    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public com.commerce.api.order.dto.SellerShipmentResponse advanceForSeller(
            Long shipmentId, Long sellerId, ShipmentStatus next,
            Long changedBy, String courier, String trackingNumber) {
        return worker.advanceForSeller(shipmentId, sellerId, next, changedBy, courier, trackingNumber);
    }

    /** ADMIN이 지정 주문의 shipment를 전진(플랫폼 null 버킷 포함). */
    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100))
    public OrderResponse advanceForAdmin(Long orderId, Long shipmentId, ShipmentStatus next,
            Long changedBy, String courier, String trackingNumber) {
        return worker.advanceForAdmin(orderId, shipmentId, next, changedBy, courier, trackingNumber);
    }
}
