package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.common.CancelReason;
import com.commerce.api.global.common.CancelReason.Fault;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.order.service.ShipmentService;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.payment.service.PaymentService;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.returns.dto.ReturnAction;
import com.commerce.api.returns.dto.ReturnCreateRequest;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.service.ReturnService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품 회수비(#8 후속 P2) — 고객 귀책 반품에서 환불액을 회수비만큼 줄이고, 그 금액을 payable에 되더한다.
 *
 * <p>회수비는 테스트 기본 OFF(0원)이므로 여기서만 {@code properties}로 켠다(#4가 배송비에 쓴 방식 그대로 —
 * 기존 회귀 그물을 손대지 않고 새 정책만 검증한다).
 *
 * <p><b>이 테스트 파일의 존재 이유</b>는 {@link #returnThenCancelOther_doesNotRefundCharge()} 한 건이다.
 * 회수비를 '그냥 환불을 덜 하는' 방식으로 구현하면 단건 반품은 정상으로 보이지만, 같은 주문의 다른 항목을
 * 취소하는 순간 취소 환불 공식이 차액을 자동 환불해 고객이 회수비를 되돌려받는다. 그 누수를 못 박는다.
 */
@SpringBootTest(properties = "app.order.return-shipping-fee=3000")
@Transactional
class ReturnShippingChargeTest {

    @Autowired private ReturnService returnService;
    @Autowired private PaymentService paymentService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ShipmentService shipmentService;

    private static final long CHARGE = 3000L;

    private OrderItem item(Long sellerId, long price) {
        return OrderItem.builder().productId(sellerId).optionId(sellerId * 11).sellerId(sellerId)
                .productName("P" + sellerId).size("M").orderPrice(price).quantity(1).build();
    }

    /**
     * 결제완료 주문 + 결제 행. prices의 각 금액이 셀러 1..N의 항목이 된다(셀러별 shipment 팬아웃).
     * 배송 전진은 {@link #deliver(Order, Long)}로 <b>셀러별</b>로 한다 — 반품과 취소가 한 주문에 공존하려면
     * 한 셀러는 배송완료(반품 가능), 다른 셀러는 미출고(취소 가능)여야 하기 때문이다.
     */
    private Order paidOrder(long... prices) {
        Order order = Order.create(100L);
        long total = 0;
        for (int i = 0; i < prices.length; i++) {
            order.addItem(item((long) (i + 1), prices[i]));
            total += prices[i];
        }
        order.markPaid();
        Order saved = orderRepository.saveAndFlush(order);

        Payment payment = Payment.ready(saved.getId(), total, "MOCK_CARD", "TOSS", "key-rsc-" + saved.getId());
        payment.markPaid("TOSS-tx-" + saved.getId());
        paymentRepository.saveAndFlush(payment);
        return saved;
    }

    /** 그 셀러의 shipment만 PAID→SHIPPING→DELIVERED로 전진(다른 셀러는 미출고로 남는다). */
    private Order deliver(Order order, Long sellerId) {
        long shipmentId = order.getShipments().stream()
                .filter(s -> sellerId.equals(s.getSellerId())).findFirst().orElseThrow().getId();
        shipmentService.advance(shipmentId, ShipmentStatus.SHIPPING, null, "CJ", "1");
        shipmentService.advance(shipmentId, ShipmentStatus.DELIVERED, null, null, null);
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    /** 단일 셀러 주문을 만들고 배송완료까지 — 회수비 단건 검증용. */
    private Order paidDeliveredOrder(long... prices) {
        Order order = paidOrder(prices);
        for (int i = 0; i < prices.length; i++) {
            order = deliver(order, (long) (i + 1));
        }
        return order;
    }

    /** 반품을 요청→승인→수거→검수(귀책 지정)→환불까지 완주시킨다. */
    private ReturnResponse runReturn(Order order, long itemId, CancelReason reason, Fault fault) {
        ReturnResponse req = returnService.create(100L, false, order.getId(),
                new ReturnCreateRequest(itemId, ReturnType.RETURN, "상세", reason, null));
        Long sellerId = req.sellerId();
        returnService.advanceForSeller(req.id(), sellerId, new ReturnStatusUpdateRequest(ReturnAction.APPROVE, null), 7L);
        returnService.advanceForSeller(req.id(), sellerId, new ReturnStatusUpdateRequest(ReturnAction.PICK_UP, null), 7L);
        returnService.advanceForSeller(req.id(), sellerId, new ReturnStatusUpdateRequest(ReturnAction.INSPECT, null, fault), 7L);
        return returnService.advanceForSeller(req.id(), sellerId, new ReturnStatusUpdateRequest(ReturnAction.REFUND, null), 7L);
    }

    private long itemIdOf(Order order, Long sellerId) {
        return order.getOrderItems().stream()
                .filter(i -> sellerId.equals(i.getSellerId())).findFirst().orElseThrow().getId();
    }

    private Payment paymentOf(Order order) {
        return paymentRepository.findByOrderIdAndStatus(order.getId(), PaymentStatus.PAID)
                .orElseGet(() -> paymentRepository.findAll().stream()
                        .filter(p -> p.getOrderId().equals(order.getId())).findFirst().orElseThrow());
    }

    @Test
    @DisplayName("고객 귀책 반품 - 회수비만큼 덜 환불하고 실지급액·차감액을 원장에 나눠 기록")
    void customerFault_deductsCharge() {
        Order order = paidDeliveredOrder(10000L);
        long itemId = itemIdOf(order, 1L);

        ReturnResponse done = runReturn(order, itemId, CancelReason.CHANGE_OF_MIND, Fault.CUSTOMER);

        assertThat(done.returnShippingFee()).isEqualTo(CHARGE);       // 신청 시점 요율 스냅샷
        assertThat(done.returnShippingCharged()).isEqualTo(CHARGE);   // 실제 차감액
        assertThat(done.refundAmount()).isEqualTo(7000L);             // 실지급액 = 실효가 10000 − 3000
        assertThat(paymentOf(order).getRefundedAmount()).isEqualTo(7000L);
    }

    @Test
    @DisplayName("셀러 귀책 반품 - 고객에게는 부과하지 않는다(전액 환불)")
    void sellerFault_noCustomerCharge() {
        Order order = paidDeliveredOrder(10000L);
        long itemId = itemIdOf(order, 1L);

        ReturnResponse done = runReturn(order, itemId, CancelReason.DEFECTIVE, Fault.SELLER);

        assertThat(done.returnShippingCharged()).isZero();
        assertThat(done.refundAmount()).isEqualTo(10000L);
        assertThat(paymentOf(order).getRefundedAmount()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("귀책 미상(NONE) - 플랫폼이 흡수한다(고객 부과 0)")
    void noneFault_platformAbsorbs() {
        Order order = paidDeliveredOrder(10000L);
        long itemId = itemIdOf(order, 1L);

        ReturnResponse done = runReturn(order, itemId, CancelReason.OTHER, Fault.NONE);

        assertThat(done.returnShippingCharged()).isZero();
        assertThat(done.refundAmount()).isEqualTo(10000L);
    }

    /**
     * ★ 이 파일의 핵심. 회수비를 payable에 되더하지 않으면 여기서 고객이 3000원을 되돌려받는다.
     *
     * <p>취소 환불 공식은 {@code refundNow = (결제액 − 환불누계) − 남은 payable}이다.
     * A를 반품(회수비 차감)한 뒤 B를 취소할 때, payable에 회수비가 없으면 잔여가 그만큼 크게 남아
     * B 취소 환불에 회수비가 얹혀 나간다.
     */
    @Test
    @DisplayName("★ 반품 후 다른 항목 취소 - 회수비가 자동 환급되지 않는다(항등식 유지)")
    void returnThenCancelOther_doesNotRefundCharge() {
        // 셀러1 배송완료(반품 가능) + 셀러2 미출고(취소 가능) — 반품과 취소가 한 주문에 공존하는 실제 형태
        Order order = deliver(paidOrder(10000L, 20000L), 1L);   // 결제 30000
        long itemA = itemIdOf(order, 1L);
        long itemB = itemIdOf(order, 2L);

        // A 반품(고객 귀책) → 7000 환불, 회수비 3000 차감
        runReturn(order, itemA, CancelReason.CHANGE_OF_MIND, Fault.CUSTOMER);
        assertThat(paymentOf(order).getRefundedAmount()).isEqualTo(7000L);

        // 항등식: 결제액 − 환불누계 == payable  (30000 − 7000 == 20000 + 회수비 3000)
        Order afterReturn = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterReturn.getPayableAmount()).isEqualTo(23000L);
        assertThat(afterReturn.getReturnShippingCharge()).isEqualTo(CHARGE);

        // B 취소 → B의 실효가 20000만 환불되어야 한다(회수비 3000은 플랫폼이 계속 보유)
        paymentService.cancelOrderItem(100L, order.getId(), itemB, false, null);

        Payment after = paymentOf(order);
        assertThat(after.getRefundedAmount()).isEqualTo(27000L);   // 7000 + 20000 — 30000이 아니다
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PAID);   // 회수비 잔여라 전액 도달 안 함

        // 전량 이탈 후에도 payable에 회수비가 남아 항등식이 유지된다
        Order afterCancel = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterCancel.getPayableAmount()).isEqualTo(CHARGE);
        assertThat(30000L - after.getRefundedAmount()).isEqualTo(afterCancel.getPayableAmount());
    }

    /**
     * 실효가보다 회수비가 큰 저가·고할인 라인. 클램프가 없으면 음수 환불이 되어
     * {@code refundForReturn}의 {@code <=0} 조기 리턴에 걸리거나(무음 손실) Payment가 400을 던진다.
     */
    @Test
    @DisplayName("실효가 < 회수비 - 실효가까지만 차감(환불 0), 항등식도 유지")
    void chargeClampedToEffectivePrice() {
        Order order = paidDeliveredOrder(2000L);   // 실효가 2000 < 회수비 3000
        long itemId = itemIdOf(order, 1L);

        ReturnResponse done = runReturn(order, itemId, CancelReason.CHANGE_OF_MIND, Fault.CUSTOMER);

        assertThat(done.returnShippingCharged()).isEqualTo(2000L);   // 3000이 아니라 실효가까지만
        assertThat(done.refundAmount()).isZero();
        assertThat(paymentOf(order).getRefundedAmount()).isZero();   // PG 호출 없음

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getPayableAmount()).isEqualTo(2000L);       // 클램프된 실차감액이 그대로 가산
        assertThat(2000L - 0L).isEqualTo(after.getPayableAmount());  // 항등식 유지
    }

    @Test
    @DisplayName("교환은 회수비 부과 대상이 아니다 - 요율 스냅샷이 0")
    void exchangeIsNotCharged() {
        // 교환은 실제 대체 옵션이 필요하므로 상품을 만들어 붙인다
        Product product = Product.builder().name("셔츠").price(10000L).description("d")
                .status(ProductStatus.ON_SALE).build();
        product.addOption(ProductOption.create("M", 5));
        product.addOption(ProductOption.create("L", 5));
        Product saved = productRepository.save(product);
        long optionM = saved.getOptions().get(0).getId();
        long optionL = saved.getOptions().get(1).getId();

        Order order = Order.create(100L);
        order.addItem(OrderItem.builder().productId(saved.getId()).optionId(optionM).sellerId(1L)
                .productName("셔츠").size("M").orderPrice(10000L).quantity(1).build());
        order.markPaid();
        order.advanceShipping(OrderStatus.SHIPPING, null, "CJ", "1");
        order.advanceShipping(OrderStatus.DELIVERED, null, null, null);
        Order savedOrder = orderRepository.saveAndFlush(order);

        ReturnResponse req = returnService.create(100L, false, savedOrder.getId(),
                new ReturnCreateRequest(savedOrder.getOrderItems().get(0).getId(), ReturnType.EXCHANGE,
                        "사이즈 변경", CancelReason.CHANGE_OF_MIND, optionL));

        // 교환은 환불이 없어 차감할 대상이 없다(추가 청구는 Payment 모델이 표현 못 함) → v1 부과 제외
        assertThat(req.returnShippingFee()).isZero();
    }
}
