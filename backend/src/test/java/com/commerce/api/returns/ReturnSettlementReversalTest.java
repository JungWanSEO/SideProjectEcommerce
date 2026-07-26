package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.exception.BusinessException;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품 정산 무손상(#3 P5) 통합 테스트 — 전액환불 클로백 누수 fix + Payout 음수 가드.
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
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "변심", null));
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
    @DisplayName("Payout 음수 가드 - 이 기간 net이 음수면 400(다음 기간 이월 상계)")
    void payoutNegativeNetRejected() {
        Seller seller = sellerRepository.save(Seller.create("셀러B", 0.10, null, null));
        Long sellerId = seller.getId();
        LocalDate today = LocalDate.now();
        // 음수 순액 항목(반품 역분개로 매출 초과 환불된 상황 모사)
        settlementRepository.save(SettlementEntry.scheduled(
                1L, 11L, "tx-neg", "TOSS", sellerId,
                -10000L, -250L, 0.025, -1000L, 0.10, 0L, null, today));

        assertThatThrownBy(() -> payoutService.create(new PayoutCreateRequest(sellerId, today, today)))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
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
