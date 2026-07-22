package com.commerce.api.order.service;

import com.commerce.api.coupon.service.MemberCouponService;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.service.StockReservationService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제되지 않은 주문(PENDING) 만료 배치 — 장바구니를 주문으로 만든 뒤 결제하지 않고 떠난 건을 정리한다.
 *
 * <p><b>왜 필요한가</b>: 체크아웃은 PENDING 주문을 만들지만 결제까지 가는 비율은 100%가 아니다. 방치된 PENDING이
 * 영구 누적되면 어드민 주문 목록이 쓰레기로 덮이고 "전체 주문 수" KPI가 부풀려진다(대시보드는 모든 상태를 센다).
 *
 * <p><b>재고와의 관계</b>: 지금 PENDING은 재고를 잡지 않는다(차감은 결제 승인 시점) → 만료가 재고를 푸는 일은 없다.
 * 재고 예약(TTL 홀드)을 도입하면 <b>이 배치가 예약 해제 지점</b>이 된다 — 그때 확장할 자리.
 *
 * <p><b>주문별 독립 트랜잭션</b>: 배치는 트랜잭션 없이 만료 후보를 훑고, 각 주문 취소는 {@link OrderExpiryWorker}의
 * 새 트랜잭션으로 처리한다(프록시 경유 — self-invocation 회피). 그래서 <b>결제와 동시 실행</b>돼 한 주문이
 * 낙관락 충돌(그 사이 결제됨)을 내도 그 주문만 건너뛰고 나머지는 계속 만료된다(배치 전체가 실패하지 않는다).
 *
 * <p><b>테스트에선 끈다</b>({@code app.order.expiry.enabled=false}): 켜두면 테스트가 10분 경계를 넘는 순간
 * 이 배치가 돌아 다른 테스트가 만든 PENDING 주문을 취소해 간헐적 실패(flaky)를 만든다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.order.expiry.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderExpiryService {

    private final OrderRepository orderRepository;
    private final OrderExpiryWorker worker;   // 주문별 새 트랜잭션 위임(프록시 경유)

    /**
     * 결제 대기 유효시간(분). 이 시간이 지나도 PENDING이면 취소한다.
     * 설정값으로 뺀 이유: 운영에서 조정 가능해야 하고, 테스트가 짧은 TTL로 경계를 검증할 수 있어야 한다.
     */
    @Value("${app.order.pending-ttl-minutes:30}")
    private int pendingTtlMinutes;

    /**
     * 만료된 PENDING 주문을 취소한다. 10분마다 실행. 반환 = 실제 취소한 주문 수.
     * 각 주문은 worker의 독립 트랜잭션으로 취소하고, 결제와의 경합(낙관락 충돌)은 그 주문만 스킵한다.
     */
    @Scheduled(cron = "0 */10 * * * *")
    public int expirePendingOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(pendingTtlMinutes);
        List<Order> expired = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, deadline);
        int cancelled = 0;
        for (Order order : expired) {
            try {
                worker.expireOne(order.getId());
                cancelled++;
            } catch (ConcurrencyFailureException e) {
                // 그 사이 결제/취소돼 낙관락 충돌 — 이 주문만 건너뛴다(이미 확정된 상태를 뒤집지 않음).
                log.debug("[order-expiry] 주문 {} 만료 경합으로 스킵", order.getId());
            }
        }
        if (cancelled > 0) {
            log.info("[order-expiry] 결제 대기 만료 주문 {}건 취소 (기준: {}분 경과)", cancelled, pendingTtlMinutes);
        }
        return cancelled;
    }
}

/**
 * 만료 주문을 <b>한 건씩 자기 트랜잭션</b>으로 취소하는 워커 — OrderExpiryService가 프록시로 호출해 주문별 격리를 얻는다.
 * 재조회 후 여전히 PENDING일 때만 취소하고(멱등), Order @Version이 결제와의 경합을 커밋 시 충돌로 드러낸다.
 */
@Component
@RequiredArgsConstructor
class OrderExpiryWorker {

    private final OrderRepository orderRepository;
    private final MemberCouponService memberCouponService;         // 만료 취소 시 발급형 쿠폰 복원(수동취소와 대칭)
    private final StockReservationService stockReservationService; // 만료 취소 시 재고 예약 해제(#2)

    @Transactional
    public void expireOne(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING) {
            return;   // 그 사이 결제/취소/삭제됨 — 스킵(멱등)
        }
        order.cancel(null, "결제 대기 만료");   // 시스템 취소 → changedBy=null. 커밋 시 @Version이 경합을 충돌로
        memberCouponService.release(order.getMemberId(), order.getCouponCode());
        stockReservationService.releaseForOrder(orderId);
    }
}
