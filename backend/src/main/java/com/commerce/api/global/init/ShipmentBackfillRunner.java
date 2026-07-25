package com.commerce.api.global.init;

import com.commerce.api.order.service.ShipmentBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * shipment 백필 기동 러너(#1 P2) — 기동 직후 {@link ShipmentBackfillService#backfillAll()}로 기존 PURCHASED
 * 주문에 셀러별 shipment를 소급 생성한다. per-order 멱등이라 매 기동 돌아도 안전(이미 채워진 주문은 skip).
 *
 * <p>테스트에선 {@code app.shipment.backfill.enabled=false}로 꺼 둔다(다른 백그라운드 러너와 동일 컨벤션 —
 * outbox.relay/order.expiry). 테스트 데이터는 각 테스트 트랜잭션이 만들고, 백필 로직은 전용 서비스 테스트로 검증한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.shipment.backfill.enabled", havingValue = "true", matchIfMissing = true)
public class ShipmentBackfillRunner implements CommandLineRunner {

    private final ShipmentBackfillService backfillService;

    @Override
    public void run(String... args) {
        int created = backfillService.backfillAll();
        if (created > 0) {
            log.info("[shipment-backfill] 기존 PURCHASED 주문 {}건에 shipment 소급 생성", created);
        }
    }
}
