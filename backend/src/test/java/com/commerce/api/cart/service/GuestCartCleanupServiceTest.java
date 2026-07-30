package com.commerce.api.cart.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.cart.entity.Cart;
import com.commerce.api.cart.repository.CartRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게스트 장바구니 TTL 정리 배치(#7 후속) 검증 — <b>무엇을 지우고 무엇을 남기는가</b>가 전부다.
 *
 * <p>배치 빈은 테스트 프로파일에서 꺼져 있다({@code app.cart.guest-cleanup.enabled=false} — 켜두면 스케줄이
 * 다른 테스트의 카트를 지워 flaky). 그래서 실 리포지토리로 <b>직접 조립</b>해 경계만 검증한다.
 *
 * <p>{@code updatedAt}은 JPA Auditing이 채우므로 테스트에선 리플렉션으로 과거 시각을 심는다(시간을 기다릴 수 없다).
 */
@SpringBootTest
@Transactional
class GuestCartCleanupServiceTest {

    @Autowired private CartRepository cartRepository;
    @PersistenceContext private EntityManager em;

    /** TTL(일) — 서비스의 @Value 필드를 직접 심는다(수동 조립이라 프로퍼티 바인딩이 없다). */
    private static final int TTL_DAYS = 30;

    private GuestCartCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        // 배치 빈은 테스트 프로파일에서 꺼져 있다(@ConditionalOnProperty) → 실 리포지토리로 직접 조립.
        cleanupService = new GuestCartCleanupService(cartRepository);
        ReflectionTestUtils.setField(cleanupService, "guestTtlDays", TTL_DAYS);
    }

    @Test
    @DisplayName("방치된 게스트 카트만 지운다 — 최근 활동 게스트·회원 카트는 남는다")
    void deletesOnlyAbandonedGuestCarts() {
        Long abandonedGuest = saveGuestCart(daysAgo(40));   // TTL(30일) 초과 → 대상
        Long activeGuest = saveGuestCart(daysAgo(3));       // 최근 활동 → 보존
        Long memberCart = saveMemberCart(daysAgo(400));     // 아무리 오래돼도 회원 카트는 보존

        int deleted = cleanupService.cleanupAbandonedGuestCarts();

        assertThat(deleted).isPositive();
        assertThat(cartRepository.findById(abandonedGuest)).isEmpty();
        assertThat(cartRepository.findById(activeGuest)).isPresent();
        // 회원 카트는 계정에 딸린 자산 — 만료 대상이 아니다(쿠키 유실로 고아가 되는 건 게스트뿐)
        assertThat(cartRepository.findById(memberCart)).isPresent();
    }

    @Test
    @DisplayName("지울 게 없으면 0을 반환하고 아무것도 건드리지 않는다")
    void returnsZeroWhenNothingToClean() {
        Long activeGuest = saveGuestCart(daysAgo(1));

        int deleted = cleanupService.cleanupAbandonedGuestCarts();

        assertThat(cartRepository.findById(activeGuest)).isPresent();
        assertThat(deleted).isZero();
    }

    private static LocalDateTime daysAgo(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    /** 게스트 카트 + updatedAt 과거로 심기(Auditing이 채운 값을 덮어 flush). */
    private Long saveGuestCart(LocalDateTime updatedAt) {
        Cart cart = cartRepository.saveAndFlush(Cart.createForGuest(UUID.randomUUID().toString()));
        return backdate(cart, updatedAt);
    }

    private Long saveMemberCart(LocalDateTime updatedAt) {
        Cart cart = cartRepository.saveAndFlush(Cart.create(System.nanoTime()));
        return backdate(cart, updatedAt);
    }

    /**
     * updatedAt을 과거로 — <b>벌크 UPDATE로</b> 심는다.
     *
     * <p>엔티티 필드를 리플렉션으로 바꾼 뒤 save하면 JPA Auditing(@LastModifiedDate)이 flush 시점에
     * <b>다시 now로 덮어써서</b> 과거 시각이 남지 않는다(한 번 겪었다). 벌크 UPDATE는 영속성 컨텍스트·
     * 라이프사이클 콜백을 우회하므로 값이 그대로 들어간다 — 대신 컨텍스트가 stale이라 clear가 필요하다.
     */
    private Long backdate(Cart cart, LocalDateTime updatedAt) {
        Long id = cart.getId();
        em.createQuery("update Cart c set c.updatedAt = :t where c.id = :id")
                .setParameter("t", updatedAt)
                .setParameter("id", id)
                .executeUpdate();
        em.clear();
        return id;
    }
}
