package com.commerce.api.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.settlement.dto.PayoutCreateRequest;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.repository.PayoutRepository;
import com.commerce.api.settlement.repository.SettlementRepository;
import com.commerce.api.settlement.service.PayoutService;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지급 묶음(Payout) 생성 동시성 통합 테스트.
 *
 * <p>같은 셀러·겹치는 정산 윈도우로 {@code create()}가 동시에 여러 번 들어와도, 하나의 정산 항목이
 * <b>두 묶음에 이중 편입되지 않음</b>(→ 이중지급 0)을 검증한다. 방어 = {@code SettlementRepository.claimForPayout}의
 * 원자적 조건부 UPDATE(payout_id IS NULL 대상만) + 편입 수 검증(경합 패자는 롤백). 선착순 쿠폰·재고 예약과 동형 idiom.
 *
 * <p>(적대적 money-path 스캔 확정① 회귀 방지 — 기존엔 비잠금 조회 + 조건절 없는 setter라 lost update가 열려 있었다.)
 */
@SpringBootTest
class PayoutConcurrencyTest {

    @Autowired
    private PayoutService payoutService;
    @Autowired
    private SettlementRepository settlementRepository;
    @Autowired
    private PayoutRepository payoutRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("동시 지급 묶음 생성 - 같은 정산 항목을 두 묶음이 이중 편입하지 않는다(이중지급 0)")
    void concurrentCreate_noDoublePayout() throws InterruptedException {
        // given: 셀러 한 명의 SCHEDULED·미편입 정산 항목 1건(net = 10000-250-1000 = 8750 > 0).
        Long sellerId = 90000L + (System.nanoTime() % 10000);   // 픽스처 셀러(1·2·5)와 겹치지 않는 격리용 id
        LocalDate today = LocalDate.now();
        SettlementEntry entry = SettlementEntry.scheduled(
                1L, 1L, "tx-conc-" + System.nanoTime(), "TOSS", sellerId,
                10000, 250, 0.025, 1000, 0.10, today);
        Long entryId = settlementRepository.save(entry).getId();

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        // when: 여러 스레드가 같은 셀러·윈도우로 동시에 지급 묶음을 만든다(일제히 출발해 경합 최대화).
        PayoutCreateRequest request =
                new PayoutCreateRequest(sellerId, today.minusDays(1), today.plusDays(1));
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    payoutService.create(request);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();   // 경합 패자(409) 또는 이미 편입돼 대상 없음(400)
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 항목은 원자적으로 한 번만 편입될 수 있으므로 정확히 1건만 성공 → 이중지급 0
        assertThat(success.get()).isEqualTo(1);                          // ① 성공 = 1
        assertThat(fail.get()).isEqualTo(threadCount - 1);              // ② 나머지는 실패(경합/대상없음)
        long payoutsForSeller = new TransactionTemplate(txManager).execute(s ->
                payoutRepository.findBySellerId(sellerId, Pageable.unpaged()).getTotalElements());
        assertThat(payoutsForSeller).isEqualTo(1L);                     // ③ 묶음 1건만 커밋(패자 롤백)
        SettlementEntry after = new TransactionTemplate(txManager).execute(s ->
                settlementRepository.findById(entryId).orElseThrow());
        assertThat(after.getPayoutId()).isNotNull();                    // ④ 정확히 하나의 묶음에 편입
    }
}
