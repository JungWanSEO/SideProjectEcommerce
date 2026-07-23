package com.commerce.api.order.service;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * shipment 백필(#1 P2) — P2 이전에 만들어진 기존 PURCHASED 주문에 셀러별 shipment를 소급 생성한다.
 *
 * <p>결제 시점 팬아웃({@link Order#markPaid()})은 P2 이후 결제분에만 shipment를 만든다. 그전 주문은 shipment가
 * 없으므로, 배포 직후 1회(또는 안전하게 매 기동) 이 백필로 채운다. 판정·생성은 per-order 멱등
 * ({@link Order#backfillShipments()}이 이미 shipment가 있으면 no-op)이라 재실행에 안전하다 —
 * 테이블-전역 플래그 대신 "shipment 없는 주문만" 고르는 쿼리로 부분 실패·재기동에도 견딘다.
 */
@Service
@RequiredArgsConstructor
public class ShipmentBackfillService {

    private final OrderRepository orderRepository;

    /**
     * shipment 없는 PURCHASED 주문에 현재 상태를 상속한 shipment를 소급 생성한다.
     * @return 이번 실행으로 백필한 주문 수(cascade=ALL로 트랜잭션 커밋 시 shipment 저장).
     */
    @Transactional
    public int backfillAll() {
        List<Order> targets = orderRepository.findPurchasedWithoutShipments();
        int created = 0;
        for (Order order : targets) {
            if (order.backfillShipments()) {
                created++;
            }
        }
        return created;
    }
}
