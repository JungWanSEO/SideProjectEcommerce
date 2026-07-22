package com.commerce.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderCreateRequest;
import com.commerce.api.order.dto.OrderCreateRequest.OrderItemRequest;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재고 예약 동시성 통합 테스트(#2).
 *
 * <p>오버셀 차단이 "결제(pay)"에서 "주문 생성(예약)" 시점으로 옮겨졌다 — 주문 생성 시 원자적 조건부 UPDATE
 * ({@code reserved += q WHERE stock − reserved >= q})로 재고를 잡으므로, 여러 스레드가 같은 옵션을 동시에
 * 주문해도 <b>정확히 재고만큼만</b> 예약에 성공하고 나머지는 즉시 409(품절)로 실패한다(선착순 쿠폰과 동형).
 */
@SpringBootTest
class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private PlatformTransactionManager txManager;   // 최종 재고를 트랜잭션 안에서 읽기 위함(옵션 지연 로딩)

    @Test
    @DisplayName("동시 주문 생성 - 재고 예약으로 오버셀 없음 (30 동시 → 정확히 재고 10만 성공, 20은 품절 409)")
    void concurrentOrderCreation_reservesExactlyStock() throws InterruptedException {
        int initialStock = 10;
        int threadCount = 30;
        Product product = Product.builder()
                .name("한정수량상품").price(10000L).description("desc").status(ProductStatus.ON_SALE).build();
        product.addOption(ProductOption.create("M", initialStock));
        Product saved = productRepository.save(product);
        Long optionId = saved.getOptions().get(0).getId();

        // 풀 크기 = 스레드 수: ready 게이트를 쓰려면 모든 태스크가 동시에 '출발선'에 서야 한다
        //   (풀 < 태스크면 초과분이 큐에 남아 ready가 0에 못 닿고, 앞 태스크는 start.await로 스레드를 물어 데드락).
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);   // 동시 출발 게이트
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    orderService.create(1L,
                            new OrderCreateRequest(List.of(new OrderItemRequest(optionId, 1))));
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    soldOut.incrementAndGet();   // 409 품절(가용재고 소진)
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();                 // 30 스레드 동시 출발
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 정확히 재고만큼만 예약 성공, 나머지는 품절 — 오버셀 0.
        ProductOption opt = new TransactionTemplate(txManager).execute(s ->
                productRepository.findById(saved.getId()).orElseThrow().getOptions().get(0));
        assertThat(success.get()).isEqualTo(initialStock);                    // ① 정확히 10만 성공
        assertThat(soldOut.get()).isEqualTo(threadCount - initialStock);     // ② 20은 품절 409
        assertThat(opt.getReserved()).isEqualTo(initialStock);               // ③ reserved = 10
        assertThat(opt.available()).isZero();                                // ④ 가용재고 0
        assertThat(opt.getStock()).isEqualTo(initialStock);                  // ⑤ 물리 재고는 미결제라 그대로 10
    }
}
