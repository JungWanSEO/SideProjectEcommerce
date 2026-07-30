package com.commerce.api.cart.service;

import com.commerce.api.cart.entity.Cart;
import com.commerce.api.cart.repository.CartRepository;
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
 * 게스트 장바구니 TTL 정리 배치(#7 후속) — 오래 방치된 <b>게스트</b> 카트를 지운다.
 *
 * <p><b>왜 필요한가</b>: 게스트 카트는 httpOnly 쿠키의 토큰으로만 소유가 확인된다. 쿠키가 사라지거나(브라우저
 * 정리·기기 변경) 로그인 병합 없이 이탈하면 그 카트는 <b>영구 고아</b>가 된다 — 아무도 다시 도달할 수 없는데
 * 행은 계속 쌓인다. 회원 카트는 계정에 딸려 있어 언제든 다시 접근하므로 정리 대상이 아니다.
 *
 * <p><b>기준은 {@code updatedAt}</b>: 담기·수량 변경마다 갱신되므로 "마지막 활동 후 N일"이 된다.
 * {@code createdAt}으로 하면 오래전에 만들어 계속 쓰는 카트까지 지워 버린다.
 *
 * <p>주문 만료 배치({@code OrderExpiryService})와 같은 결: 설정값으로 TTL을 빼고, 테스트에선 끈다
 * ({@code app.cart.guest-cleanup.enabled=false}) — 켜두면 배치가 다른 테스트의 카트를 지워 간헐 실패를 만든다.
 *
 * <p>재고 예약과 무관하다 — 예약은 주문 생성 시점에 잡히고 카트는 예약을 잡지 않는다. 따라서 카트를 지워도
 * 풀어야 할 재고가 없다(주문 만료 배치와 다른 점).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.cart.guest-cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class GuestCartCleanupService {

    private final CartRepository cartRepository;

    /**
     * 게스트 카트 유효기간(일). 마지막 활동 후 이 기간이 지나면 정리한다.
     * 설정값으로 뺀 이유는 주문 TTL과 같다 — 운영에서 조정 가능해야 하고, 테스트가 짧은 값으로 경계를 검증한다.
     */
    @Value("${app.cart.guest-ttl-days:30}")
    private int guestTtlDays;

    /**
     * 방치된 게스트 카트를 삭제한다. 매일 04:10(트래픽 적은 시간)에 실행. 반환 = 삭제한 카트 수.
     * 항목(cart_item)은 애그리거트 내부라 cascade로 함께 지워진다.
     */
    @Scheduled(cron = "0 10 4 * * *")
    @Transactional
    public int cleanupAbandonedGuestCarts() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(guestTtlDays);
        List<Cart> abandoned = cartRepository.findByMemberIdIsNullAndUpdatedAtBefore(threshold);
        if (abandoned.isEmpty()) {
            return 0;
        }
        cartRepository.deleteAll(abandoned);
        log.info("[cart-cleanup] 방치된 게스트 장바구니 {}건 삭제 (기준: 마지막 활동 후 {}일)",
                abandoned.size(), guestTtlDays);
        return abandoned.size();
    }
}
