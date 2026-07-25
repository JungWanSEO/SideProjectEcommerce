package com.commerce.api.order.service;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * shipment 백필의 <b>주문별 트랜잭션 단위</b>(#1 리뷰 #4·#6 교정) — {@link ShipmentBackfillService}가 ID마다 호출한다.
 *
 * <p>부모 주문을 {@link OrderRepository#findByIdForUpdate 비관적 쓰기 락}으로 잡고 fresh로 재확인한 뒤 백필하므로,
 * 백필 실행 중 들어오는 <b>동시 취소</b>와 같은 락으로 직렬화된다(취소가 먼저면 backfillShipments가 CANCELLED로 skip,
 * 백필이 먼저면 취소가 shipment를 보고 정상 rollup). 주문마다 커밋해 대량에서도 단일 거대 트랜잭션·힙 적재를 피한다.
 */
@Component
@RequiredArgsConstructor
public class ShipmentBackfillWorker {

    private final OrderRepository orderRepository;

    /**
     * 주문 1건 백필 — 락 후 현재 상태를 재확인한다({@link Order#backfillShipments()}이 이미 shipment가 있거나
     * CANCELLED면 no-op). 락을 못 얻거나 주문이 사라졌으면 조용히 건너뛴다(다음 기동에 재시도, per-order 멱등).
     * @return 이번 호출로 shipment를 생성했으면 true.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean backfillOne(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .map(Order::backfillShipments)
                .orElse(false);
    }
}
