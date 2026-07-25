package com.commerce.api.order.service;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * shipment 백필(#1 P2) — P2 이전에 만들어진 기존 PURCHASED 주문에 셀러별 shipment를 소급 생성한다.
 *
 * <p>결제 시점 팬아웃({@link Order#markPaid()})은 P2 이후 결제분에만 shipment를 만든다. 그전 주문은 shipment가
 * 없으므로, 배포 직후 1회(또는 안전하게 매 기동) 이 백필로 채운다. 판정·생성은 per-order 멱등
 * ({@link Order#backfillShipments()}이 이미 shipment가 있으면 no-op)이라 재실행에 안전하다.
 *
 * <p>이 오케스트레이터는 <b>트랜잭션이 아니다</b>(리뷰 #4·#6 교정) — 후보 ID만 모으고, 주문마다
 * {@link ShipmentBackfillWorker#backfillOne}(비관 락 + 개별 트랜잭션)에 위임한다. 그래서 (a) 백필 중 들어오는
 * 동시 취소와 주문별로 직렬화되고(취소된 주문 부활 방지), (b) 대량이어도 단일 거대 트랜잭션·전량 힙 적재를 피한다.
 */
@Service
@RequiredArgsConstructor
public class ShipmentBackfillService {

    private final OrderRepository orderRepository;
    private final ShipmentBackfillWorker worker;

    /**
     * shipment 없는 PURCHASED 주문을 주문별 개별 트랜잭션으로 백필한다.
     * @return 이번 실행으로 백필한 주문 수.
     */
    public int backfillAll() {
        List<Long> ids = orderRepository.findPurchasedWithoutShipmentIds();
        int created = 0;
        for (Long id : ids) {
            if (worker.backfillOne(id)) {
                created++;
            }
        }
        return created;
    }
}
