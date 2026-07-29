package com.commerce.api.global.init;

import com.commerce.api.address.entity.Address;
import com.commerce.api.address.repository.AddressRepository;
import com.commerce.api.cart.dto.CartItemAddRequest;
import com.commerce.api.cart.service.CartOwner;
import com.commerce.api.cart.service.CartService;
import com.commerce.api.global.common.CancelReason;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.dto.CheckoutRequest;
import com.commerce.api.order.dto.OrderResponse;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.service.OrderProcessor;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.dto.PaymentRequest;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.gateway.AbstractMockPaymentGateway;
import com.commerce.api.payment.gateway.PaymentGateway;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.gateway.PgSettlementRecord;
import com.commerce.api.payment.gateway.PgSettlementStatus;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.payment.service.PaymentService;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.repository.ReturnRequestRepository;
import com.commerce.api.settlement.dto.PayoutCreateRequest;
import com.commerce.api.settlement.repository.PayoutRepository;
import com.commerce.api.settlement.service.PayoutService;
import com.commerce.api.settlement.service.ReconciliationService;
import com.commerce.api.settlement.service.SettlementService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 데모 <b>돈 흐름</b> 시드(dev 전용) — 결제 → 셀러별 정산 → 지급(payout) → PG 대사 → 환불/반품 역분개까지
 * 화면에 실제 데이터가 보이게 만든다.
 *
 * <p><b>왜 필요한가</b>: 신호용 데모 주문은 {@code Order.markPaid()}만 호출해 결제행(Payment)이 없다. 그런데
 * 정산 배치는 <b>PAID 결제</b>를 읽어 (결제×셀러)로 분해하므로, 결제가 없으면 이 프로젝트의 헤드라인인
 * 정산·대사·지급 화면이 통째로 비어 버린다(실제로 정산 1건·payout 0·대사 0이었다).
 *
 * <p><b>진짜 경로로 태운다</b>: 장바구니 담기 → 체크아웃(배송지·쿠폰·멱등키) → 결제(PG 라우팅). 모의 PG가
 * <b>자기 원장</b>에 승인 기록을 남겨야 대사가 의미를 갖기 때문에, Payment 행을 직접 만들지 않고 실제 결제
 * 서비스를 호출한다. 취소·반품도 운영 경로 그대로라 역분개·클로백이 진짜로 발생한다.
 *
 * <p><b>트랜잭션</b>: 이 클래스는 트랜잭션을 열지 않는다. 결제({@code PaymentService.pay})가 낙관락 재시도를
 * 위해 자기 트랜잭션 경계를 직접 관리하므로, 바깥 트랜잭션에 묶으면 재시도가 깨진다 — 그래서
 * {@link DemoDataInitializer}가 시드 트랜잭션이 <b>커밋된 뒤</b> 별도로 호출한다.
 *
 * <p><b>멱등</b>: 체크아웃·결제 모두 멱등키(`demo-*`)로 재실행을 흡수하고, 정산/대사/지급도 이미 있으면 건너뛴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
class DemoMoneyFlowSeeder {

    private final MemberRepository memberRepository;
    private final jakarta.persistence.EntityManager em;   // 트랜잭션 밖이라 스칼라 쿼리로만 읽는다
    private final AddressRepository addressRepository;
    private final CartService cartService;
    private final OrderProcessor orderProcessor;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRouter paymentGatewayRouter;
    private final SettlementService settlementService;
    private final PayoutService payoutService;
    private final PayoutRepository payoutRepository;
    private final ReconciliationService reconciliationService;
    private final com.commerce.api.returns.service.ReturnService returnService;
    private final ReturnRequestRepository returnRequestRepository;
    private final com.commerce.api.seller.repository.SellerRepository sellerRepository;

    /** 데모 결제 시나리오 — (구매자 인덱스, 상품명들, PG, 쿠폰코드). PG를 섞어 다중 PG 라우팅·대사 분류를 보이게 한다. */
    private static final List<Purchase> PURCHASES = List.of(
            new Purchase(0, List.of("오버핏 후디", "와이드 슬랙스"), "TOSS", null),          // 멀티셀러 결제
            new Purchase(1, List.of("캐시미어 니트"), "KAKAOPAY", "WELCOME5000"),          // 쿠폰 할인 배분
            new Purchase(2, List.of("레더 스니커즈", "코튼 볼캡"), "TOSS", null),           // 반품 시나리오 대상
            new Purchase(0, List.of("미니 토트백"), "KAKAOPAY", null));                    // 전체취소 → 환불 시나리오

    private record Purchase(int memberIndex, List<String> productNames, String provider, String couponCode) {
    }

    private static final List<String> DEMO_EMAILS =
            List.of("demo1@commerce.com", "demo2@commerce.com", "demo3@commerce.com");

    /** 대사 윈도우 — 정산일은 오늘+지급유예라 넉넉히 잡는다(윈도우가 좁으면 방금 만든 정산이 빠진다). */
    private static final int WINDOW_DAYS = 60;

    void seed() {
        List<Member> buyers = DEMO_EMAILS.stream()
                .map(email -> memberRepository.findByEmail(email).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (buyers.size() < DEMO_EMAILS.size()) {
            return;   // 회원 시드가 아직 안 돌았다면 다음 기동에
        }
        restoreGatewayLedger();   // 재기동 대비: 기존 결제를 모의 PG 원장에 되살린다(안 하면 대사가 전부 누락 판정)

        int paid = payDemoOrders(buyers);
        settlementService.run();          // PAID 결제 → (결제×셀러) 정산 항목 (이미 정산된 결제는 건너뜀)
        runCancellationScenario(buyers);  // 전체취소 → 환불(PG 원장 REFUNDED) → 대사에서 상태 불일치로 잡힌다
        runReturnScenario(buyers);        // 배송완료 → 반품 요청·승인·수거·검수·환불(실효가)
        settlementService.reverseRefunds();   // 환불분 역분개(정산 상계) — 셀러 과다정산 방지
        createPayout();                       // 셀러1 지급 묶음
        var result = reconciliationService.reconcile();   // 우리 장부 ↔ PG 원장 대조

        log.info("[demo-seed] 돈 흐름 — 결제 {}건 · 정산/역분개 반영 · 대사 결과 매칭 {}건, 불일치 {}건",
                paid, result.matched(), result.totalMismatches());
    }

    /**
     * 데모 결제 — 장바구니 → 체크아웃 → 결제(실제 경로). 멱등키가 있어 재기동해도 같은 주문·결제를 재사용한다.
     *
     * @return 이번에 새로 결제된 건수
     */
    private int payDemoOrders(List<Member> buyers) {
        int paid = 0;
        for (int i = 0; i < PURCHASES.size(); i++) {
            Purchase spec = PURCHASES.get(i);
            String key = "demo-pay-" + i;
            if (paymentRepository.findByIdempotencyKey(key).isPresent()) {
                continue;   // 이미 결제됨(멱등)
            }
            Member buyer = buyers.get(spec.memberIndex());
            Long addressId = ensureAddress(buyer);
            try {
                for (String productName : spec.productNames()) {
                    Long optionId = firstOptionId(productName);
                    if (optionId != null) {
                        cartService.addItem(new CartOwner(buyer.getId(), null), new CartItemAddRequest(optionId, 1));
                    }
                }
                OrderResponse order = orderProcessor.checkout(buyer.getId(),
                        new CheckoutRequest(addressId, "부재 시 문 앞에 놓아주세요", spec.couponCode(), "demo-order-" + i));
                paymentService.pay(buyer.getId(),
                        new PaymentRequest(order.id(), key, "MOCK_CARD", spec.provider()));
                paid++;
            } catch (RuntimeException e) {
                // 재고 부족·쿠폰 조건 미달 등으로 한 건이 실패해도 나머지 시나리오는 계속 만든다(데모 데이터일 뿐).
                log.warn("[demo-seed] 데모 결제 {}건 스킵 — {}", i, e.getMessage());
            }
        }
        return paid;
    }

    /** 전체 취소 → 환불. 모의 PG 원장이 REFUNDED로 바뀌어 <b>정산 후 환불</b> 상태 불일치가 대사에 잡힌다. */
    private void runCancellationScenario(List<Member> buyers) {
        Optional<Payment> target = paymentRepository.findByIdempotencyKey("demo-pay-3")
                .filter(p -> p.getStatus() == PaymentStatus.PAID);
        if (target.isEmpty()) {
            return;   // 아직 결제 안 됐거나 이미 취소됨
        }
        Member buyer = buyers.get(PURCHASES.get(3).memberIndex());
        try {
            paymentService.cancelOrder(buyer.getId(), target.get().getOrderId(), false, CancelReason.CHANGE_OF_MIND);
        } catch (RuntimeException e) {
            log.warn("[demo-seed] 데모 취소 시나리오 스킵 — {}", e.getMessage());
        }
    }

    /** 반품 1건 — 배송완료까지 전진시킨 뒤 요청→승인→수거→검수→환불. 반품 화면·역분개 데모의 재료. */
    private void runReturnScenario(List<Member> buyers) {
        Optional<Payment> target = paymentRepository.findByIdempotencyKey("demo-pay-2")
                .filter(p -> p.getStatus() == PaymentStatus.PAID);
        if (target.isEmpty()) {
            return;
        }
        Long orderId = target.get().getOrderId();
        Member buyer = buyers.get(PURCHASES.get(2).memberIndex());
        if (returnRequestRepository.findByMemberId(buyer.getId(), PageRequest.of(0, 1)).hasContent()) {
            return;   // 이미 반품 데이터가 있다(멱등)
        }
        try {
            orderService.advanceShipping(orderId, OrderStatus.SHIPPING, null, "CJ대한통운", "1234567890");
            OrderResponse delivered = orderService.advanceShipping(orderId, OrderStatus.DELIVERED, null, null, null);
            var item = delivered.items().stream().filter(i -> i.sellerId() != null).findFirst().orElse(null);
            if (item == null) {
                return;
            }
            ReturnResponse request = returnService.create(buyer.getId(), false, orderId,
                    new ReturnCreateRequest(item.id(), ReturnType.RETURN, "생각한 색상과 달라요",
                            CancelReason.CHANGE_OF_MIND, null));
            for (ReturnAction action : List.of(ReturnAction.APPROVE, ReturnAction.PICK_UP,
                    ReturnAction.INSPECT, ReturnAction.REFUND)) {
                returnService.advanceForSeller(request.id(), item.sellerId(),
                        new ReturnStatusUpdateRequest(action, null), null);
            }
        } catch (RuntimeException e) {
            log.warn("[demo-seed] 데모 반품 시나리오 스킵 — {}", e.getMessage());
        }
    }

    /** 셀러1 지급 묶음 1건 — 이미 있으면 건너뛴다. 지급할 정산 항목이 없으면 400이라 조용히 스킵. */
    private void createPayout() {
        if (payoutRepository.count() > 0) {
            return;
        }
        Long sellerId = sellerRepository.findByName("메종클레이").map(s -> s.getId()).orElse(null);
        if (sellerId == null) {
            return;
        }
        try {
            payoutService.create(new PayoutCreateRequest(sellerId,
                    LocalDate.now().minusDays(WINDOW_DAYS), LocalDate.now().plusDays(WINDOW_DAYS)));
        } catch (RuntimeException e) {
            log.debug("[demo-seed] 지급 묶음 생성 스킵 — {}", e.getMessage());
        }
    }

    /**
     * 모의 PG 원장 복원 — DB에 남은 결제를 PG 측 기록으로 되살린다(인메모리 원장이 재기동으로 비므로).
     * 환불이 있었던 결제는 REFUNDED로 복원해 "정산 후 환불" 불일치가 재기동 후에도 같은 결과를 낸다.
     */
    private void restoreGatewayLedger() {
        List<Payment> payments = paymentRepository.findByStatusIn(
                List.of(PaymentStatus.PAID, PaymentStatus.CANCELLED));
        for (Payment payment : payments) {
            if (payment.getPgTransactionId() == null) {
                continue;
            }
            PaymentGateway gateway = paymentGatewayRouter.resolve(payment.getProvider());
            if (gateway instanceof AbstractMockPaymentGateway mock) {
                PgSettlementStatus status = payment.getRefundedAmount() > 0
                        ? PgSettlementStatus.REFUNDED : PgSettlementStatus.PAID;
                mock.restore(new PgSettlementRecord(payment.getProvider(), payment.getPgTransactionId(),
                        payment.getAmount(), status, payment.getCreatedAt().toLocalDate()));
            }
        }
    }

    /** 데모 배송지(회원당 1건). 체크아웃이 배송지 스냅샷을 요구하므로 없으면 만든다. */
    private Long ensureAddress(Member buyer) {
        return addressRepository.findFirstByMemberIdOrderByCreatedAtDesc(buyer.getId())
                .map(Address::getId)
                .orElseGet(() -> addressRepository.save(Address.create(buyer.getId(),
                        buyer.getNickname(), "010-0000-0000", "06236",
                        "서울특별시 강남구 테헤란로 123", "4층")).getId());
    }

    /**
     * 상품명 → 첫 옵션(SKU) id. <b>스칼라 JPQL</b>로 뽑는다 — 이 시더는 트랜잭션 밖이라 엔티티를 들고 나오면
     * 지연 로딩된 옵션 컬렉션에 접근하는 순간 세션이 없어 터진다(실제로 한 번 겪었다).
     */
    private Long firstOptionId(String productName) {
        return em.createQuery("select o.id from ProductOption o where o.product.name = :name order by o.id asc",
                        Long.class)
                .setParameter("name", productName)
                .setMaxResults(1)
                .getResultList().stream().findFirst().orElse(null);
    }
}
