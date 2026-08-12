package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.service.ReturnService;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.settlement.dto.PayoutCreateRequest;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.repository.SettlementRepository;
import com.commerce.api.settlement.service.PayoutService;
import com.commerce.api.settlement.service.SettlementService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품 정산 무손상(#3 P5) 통합 테스트 — 전액환불 클로백 누수 fix + 음수 정산 이월(#8 후속 P6).
 */
@SpringBootTest
@Transactional
class ReturnSettlementReversalTest {

    @Autowired private ReturnService returnService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private SettlementService settlementService;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private PayoutService payoutService;

    @Test
    @DisplayName("클로백 누수 fix - 정산 후 전액환불(Payment CANCELLED)돼도 역분개가 돈다(셀러 과다정산 차단)")
    void reversalRunsAfterFullRefund() {
        Seller seller = sellerRepository.save(Seller.create("셀러A", 0.10, null, null));
        Long sellerId = seller.getId();

        // 배송완료 주문(그 셀러 단일 항목 5000) + PAID 결제
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder().productId(1L).optionId(11L).sellerId(sellerId)
                .productName("P").size("M").orderPrice(5000L).quantity(1).build());
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1");
        order.advanceShipping(OrderStatus.DELIVERED, null, null, null);
        Order saved = orderRepository.saveAndFlush(order);
        long orderId = saved.getId();
        long itemId = saved.getOrderItems().get(0).getId();

        Payment payment = Payment.ready(orderId, 5000L, "MOCK", "TOSS", "key-s-" + orderId);
        payment.markPaid("TOSS-tx-" + orderId);
        paymentRepository.saveAndFlush(payment);

        // 정방향 정산 → 셀러 SettlementEntry 생성
        settlementService.run();
        long settledNetBefore = sellerNet(sellerId);
        assertThat(settledNetBefore).isGreaterThan(0);   // 정산됨

        // 반품 전액환불 → Payment CANCELLED, OrderItem RETURNED
        ReturnResponse req = returnService.create(100L, false, orderId,
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "변심", null, null));
        returnService.advanceForSeller(req.id(), sellerId, act(ReturnAction.APPROVE), 1L);
        returnService.advanceForSeller(req.id(), sellerId, act(ReturnAction.PICK_UP), 1L);
        returnService.advanceForSeller(req.id(), sellerId, act(ReturnAction.INSPECT), 1L);
        returnService.advanceForSeller(req.id(), sellerId, act(ReturnAction.REFUND), 1L);

        // 역분개 — Payment가 CANCELLED여도 후보에 포함돼 돈다(예전엔 PAID만 스캔해 누락 → 과다정산)
        var result = settlementService.reverseRefunds();
        assertThat(result.reversedCount()).isGreaterThan(0);          // 역분개 생성됨(누수 아님)
        assertThat(sellerNet(sellerId)).isZero();                     // 원 정산 + 음수 역분개 = 0 (셀러 net 상계)
    }

    @Test
    @DisplayName("음수 정산 - 지급은 0원이지만 묶음은 만들고 부족분을 다음 기간으로 이월한다(#8 후속)")
    void negativeNetCarriesOver() {
        Seller seller = sellerRepository.save(Seller.create("셀러B", 0.10, null, null));
        Long sellerId = seller.getId();
        LocalDate today = LocalDate.now();
        // 음수 순액 항목(반품 역분개로 매출 초과 환불된 상황 모사)
        settlementRepository.save(SettlementEntry.scheduled(
                1L, 11L, "tx-neg", "TOSS", sellerId,
                -10000L, -250L, 0.025, -1000L, 0.10, 0L, null, today));

        var payout = payoutService.create(new PayoutCreateRequest(sellerId, today, today));

        // 예전엔 여기서 400을 던져 지급 묶음 자체를 안 만들었다 → 정상 매출까지 통째로 미지급.
        // 이제는 "0원 지급 + 부족분 이월"로 남는다(음수 송금은 여전히 만들지 않는다).
        assertThat(payout.totalNet()).isZero();
        assertThat(payout.carriedOver()).isEqualTo(-8750L);   // -10000 + 250 + 1000
        assertThat(payout.entryCount()).isEqualTo(1);         // 항목은 이 묶음에 소비됐다(다음에 또 안 잡힌다)
    }

    @Test
    @DisplayName("이월 상계 - 다음 기간 매출에서 직전 이월을 선차감하고 남은 만큼만 지급")
    void carriedOverIsDeductedNextPeriod() {
        Seller seller = sellerRepository.save(Seller.create("셀러C", 0.10, null, null));
        Long sellerId = seller.getId();
        LocalDate today = LocalDate.now();

        // 1기: 음수 → 0원 지급 + -8750 이월
        settlementRepository.save(SettlementEntry.scheduled(
                1L, 11L, "tx-c1", "TOSS", sellerId, -10000L, -250L, 0.025, -1000L, 0.10, 0L, null, today));
        var first = payoutService.create(new PayoutCreateRequest(sellerId, today, today));
        assertThat(first.carriedOver()).isEqualTo(-8750L);

        // 2기: 매출 20000(net 17500) → 직전 이월 -8750을 선차감해 8750만 지급, 이월은 0으로 해소
        settlementRepository.save(SettlementEntry.scheduled(
                2L, 12L, "tx-c2", "TOSS", sellerId, 20000L, 500L, 0.025, 2000L, 0.10, 0L, null, today.plusDays(1)));
        var second = payoutService.create(
                new PayoutCreateRequest(sellerId, today.plusDays(1), today.plusDays(1)));

        assertThat(second.carriedIn()).isEqualTo(-8750L);
        assertThat(second.totalNet()).isEqualTo(8750L);   // 17500 - 8750
        assertThat(second.carriedOver()).isZero();        // 잔액 해소
    }

    private long sellerNet(Long sellerId) {
        return settlementRepository.findAll().stream()
                .filter(e -> sellerId.equals(e.getSellerId()))
                .mapToLong(SettlementEntry::getNetAmount).sum();
    }

    private ReturnStatusUpdateRequest act(ReturnAction a) {
        return new ReturnStatusUpdateRequest(a, null);
    }
}
