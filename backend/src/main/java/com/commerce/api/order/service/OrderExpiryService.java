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
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p>self-invocation 함정 회피: {@code @Scheduled}와 {@code @Transactional}을 같은 메서드에 둔다
 * (추천 배치·아웃박스 폴러와 동일한 규칙 — 프록시 경유라야 트랜잭션이 걸린다).
 *
 * <p><b>테스트에선 끈다</b>({@code app.order.expiry.enabled=false}, 아웃박스 폴러와 같은 패턴):
 * 켜두면 테스트 실행이 10분 경계를 넘는 순간 이 배치가 돌아 <b>다른 테스트가 만든 PENDING 주문을 취소</b>해
 * 간헐적 실패(flaky)를 만든다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.order.expiry.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderExpiryService {

    private final OrderRepository orderRepository;
    private final MemberCouponService memberCouponService;   // 만료 취소 시 발급형 쿠폰 복원(수동취소와 대칭)
    private final StockReservationService stockReservationService;   // 만료 취소 시 재고 예약 해제(#2)

    /**
     * 결제 대기 유효시간(분). 이 시간이 지나도 PENDING이면 취소한다.
     * 설정값으로 뺀 이유: 운영에서 조정 가능해야 하고, 테스트가 짧은 TTL로 경계를 검증할 수 있어야 한다.
     */
    @Value("${app.order.pending-ttl-minutes:30}")
    private int pendingTtlMinutes;

    /**
     * 만료된 PENDING 주문을 취소한다. 10분마다 실행. 반환 = 취소한 주문 수.
     *
     * <p>{@code Order.cancel()}을 그대로 쓴다 — 상태 전이 규칙(이미 취소된 건 예외)을 엔티티가 계속 강제하게.
     * PENDING만 고르므로 결제된 주문은 절대 건드리지 않는다.
     */
    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public int expirePendingOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(pendingTtlMinutes);
        List<Order> expired = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, deadline);
        if (expired.isEmpty()) {
            return 0;
        }

        expired.forEach(order -> {
            order.cancel(null, "결제 대기 만료");   // 시스템 취소 → changedBy=null
            // 체크아웃 때 USED로 잠긴 발급형 쿠폰을 되돌린다 — 수동취소(PaymentService.cancelOrder)와 대칭.
            //   안 하면 "결제도 안 했는데 쿠폰만 영구 소멸"된다. 코드 없음/공개형/미보유면 release가 no-op.
            memberCouponService.release(order.getMemberId(), order.getCouponCode());
            // 잡아 둔 재고 예약을 해제해 가용재고를 되돌린다(#2) — 안 하면 만료 주문이 재고를 영구히 점유.
            stockReservationService.releaseForOrder(order.getId());
        });
        log.info("[order-expiry] 결제 대기 만료 주문 {}건 취소 (기준: {}분 경과)", expired.size(), pendingTtlMinutes);
        return expired.size();
    }
}
