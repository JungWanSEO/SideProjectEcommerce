package com.commerce.api.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.commerce.api.order.dto.OrderDiscountInfo;
import com.commerce.api.order.dto.OrderResponse.OrderItemResponse;
import com.commerce.api.order.entity.OrderItemStatus;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.dto.PaymentResponse;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.service.PaymentService;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementEntryKind;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 반품 회수비의 정산 원장 반영(#8 후속 P3) — 그리고 <b>정산 게이팅 3곳</b>의 회귀 방어.
 *
 * <p>이 파일 대부분은 "새 엔트리 종류를 추가할 때 밟기 쉬운 함정"을 못 박는 것이다. 기존 정산 테스트는
 * 전부 "정방향 정산이 이미 있는" 상태만 다루기 때문에, 회수비 엔트리가 <b>정방향보다 먼저</b> 생기는
 * 순서(반품이 먼저 확정된 결제)를 아무도 검증하지 않는다. 그 순서에서만 다음 두 가지가 터진다:
 * <ul>
 *   <li>정방향 멱등 게이트가 결제 단위면 → 그 결제는 영원히 미정산 = <b>셀러 매출 전액 소실</b></li>
 *   <li>역분개 진입 게이트가 결제 단위면 → target−0 양수 diff로 정방향을 재생성 = <b>이중 지급</b></li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SettlementReturnShippingTest {

    @Mock private SettlementRepository settlementRepository;
    @Mock private PaymentService paymentService;
    @Mock private PaymentGatewayRouter paymentGatewayRouter;
    @Mock private OrderService orderService;
    @Mock private SellerRepository sellerRepository;
    @Mock private com.commerce.api.returns.service.ReturnQueryService returnQueryService;

    @InjectMocks private SettlementService settlementService;

    private static final LocalDate SETTLED = LocalDate.now().plusDays(2);

    @org.junit.jupiter.api.BeforeEach
    void defaultNoDiscount() {
        lenient().when(orderService.getOrderDiscount(anyLong())).thenReturn(OrderDiscountInfo.none());
        lenient().when(returnQueryService.getSellerFaultCharges(anyLong())).thenReturn(java.util.Map.of());
    }

    private PaymentResponse payment(Long id, Long orderId, long amount) {
        return new PaymentResponse(id, orderId, amount, PaymentStatus.PAID,
                "MOCK_CARD", "TOSS", "MOCK-tx-" + id, LocalDateTime.now());
    }

    private OrderItemResponse item(Long sellerId, long subtotal, OrderItemStatus status) {
        return new OrderItemResponse(1L, 1L, 1L, null, sellerId, "P", "M", subtotal, 1, subtotal, 0L, status, null);
    }

    private Seller sellerWithRate(Long id, double rate) {
        Seller s = Seller.create("S" + id, rate, null, null);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private List<SettlementEntry> captureSaved(int times) {
        ArgumentCaptor<SettlementEntry> captor = ArgumentCaptor.forClass(SettlementEntry.class);
        verify(settlementRepository, org.mockito.Mockito.times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    /** 회수비 3000이 발생한 주문(= 고객 귀책 반품으로 플랫폼이 보유). */
    private OrderDiscountInfo withReturnCharge(long charge) {
        return new OrderDiscountInfo(0L, null, null, 0L, charge);
    }

    @Test
    @DisplayName("정산 - 회수비가 있으면 플랫폼 회수비 엔트리(sellerId=null·RETURN_SHIPPING)를 만든다")
    void run_createsReturnShippingEntry() {
        given(paymentService.getPaidPayments()).willReturn(List.of(payment(1L, 11L, 10000L)));
        given(settlementRepository.existsByPaymentIdAndEntryKindIn(anyLong(), any())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 7000L, OrderItemStatus.ACTIVE)));
        given(orderService.getOrderDiscount(11L)).willReturn(withReturnCharge(3000L));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));

        settlementService.run();

        List<SettlementEntry> saved = captureSaved(2);
        SettlementEntry charge = saved.stream()
                .filter(e -> e.getEntryKind() == SettlementEntryKind.RETURN_SHIPPING).findFirst().orElseThrow();
        assertThat(charge.getSellerId()).isNull();              // 플랫폼 귀속
        assertThat(charge.getGrossAmount()).isEqualTo(3000L);   // PG 잔여에 실재하는 돈 → gross에 싣는다
        assertThat(charge.getPlatformFee()).isZero();
        assertThat(charge.getFee()).isEqualTo(75L);             // 회수비 몫 PG수수료도 플랫폼 부담
        assertThat(charge.getNetAmount()).isEqualTo(2925L);

        // Σgross = 셀러 몫 7000 + 회수비 3000 = 결제액 10000 (대사 정합)
        assertThat(saved.stream().mapToLong(SettlementEntry::getGrossAmount).sum()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("★ 게이트1 - 회수비 엔트리만 있는 결제도 정방향 정산을 받는다(셀러 매출 소실 방지)")
    void run_notBlockedByReturnShippingEntryAlone() {
        // 반품이 먼저 확정돼 회수비 엔트리가 이미 있는 결제. 결제 단위 멱등 게이트였다면 여기서 건너뛰어
        // 셀러 매출이 영원히 정산되지 않는다.
        given(paymentService.getPaidPayments()).willReturn(List.of(payment(1L, 11L, 10000L)));
        given(settlementRepository.existsByPaymentIdAndEntryKindIn(anyLong(), any())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 7000L, OrderItemStatus.ACTIVE)));
        given(orderService.getOrderDiscount(11L)).willReturn(withReturnCharge(3000L));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));

        settlementService.run();

        // 셀러 매출 엔트리가 실제로 만들어졌다
        assertThat(captureSaved(2)).anyMatch(e -> e.getEntryKind() == SettlementEntryKind.SALE
                && e.getSellerId() != null && e.getGrossAmount() == 7000L);
        // 게이트가 종류를 좁혀 본다는 것 자체를 고정 — 결제 단위로 되돌리면 이 검증이 깨진다
        verify(settlementRepository).existsByPaymentIdAndEntryKindIn(1L,
                List.of(SettlementEntryKind.SALE, SettlementEntryKind.SHIPPING));
    }

    @Test
    @DisplayName("★ 게이트2 - 회수비 엔트리만 있고 정방향이 없으면 역분개는 손대지 않는다(이중 지급 방지)")
    void reverse_skipsWhenOnlyReturnShippingExists() {
        given(paymentService.getSettlementReversalCandidates()).willReturn(List.of(payment(1L, 11L, 10000L)));
        given(settlementRepository.findByPaymentId(1L)).willReturn(List.of(
                SettlementEntry.returnShippingScheduled(1L, 11L, "tx", "TOSS", 3000L, 75L, 0.025, SETTLED)));

        settlementService.reverseRefunds();

        // 아무것도 만들지 않는다 — 정방향 정산은 run()의 몫. 여기서 만들면 이중 지급이다.
        verify(settlementRepository, org.mockito.Mockito.never()).save(any(SettlementEntry.class));
    }

    @Test
    @DisplayName("★ 게이트3 - 회수비 엔트리는 셀러 집계에서 배제된다(sellerId=null 버킷 오염 방지)")
    void reverse_returnShippingExcludedFromSellerBucket() {
        given(paymentService.getSettlementReversalCandidates()).willReturn(List.of(payment(1L, 11L, 10000L)));
        given(settlementRepository.findByPaymentId(1L)).willReturn(List.of(
                SettlementEntry.scheduled(1L, 11L, "tx", "TOSS", 1L, 7000L, 175L, 0.025, 700L, 0.10, SETTLED),
                SettlementEntry.returnShippingScheduled(1L, 11L, "tx", "TOSS", 3000L, 75L, 0.025, SETTLED)));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 7000L, OrderItemStatus.ACTIVE)));
        given(orderService.getOrderDiscount(11L)).willReturn(withReturnCharge(3000L));

        settlementService.reverseRefunds();

        // 셀러 몫도 회수비도 변화 없음 → 역분개 0건(멱등). 배제하지 않으면 회수비가 null 버킷에 섞여
        // 매 실행 −3000으로 상계돼 조용히 사라진다.
        verify(settlementRepository, org.mockito.Mockito.never()).save(any(SettlementEntry.class));
    }

    @Test
    @DisplayName("역분개 - 회수비가 늘면 그 차액만 추가된다(자기 target = 주문 누계)")
    void reverse_addsOnlyDeltaOfReturnCharge() {
        given(paymentService.getSettlementReversalCandidates()).willReturn(List.of(payment(1L, 11L, 10000L)));
        // 이미 3000이 반영돼 있는데 주문 누계가 5000으로 늘었다(반품이 하나 더 확정)
        given(settlementRepository.findByPaymentId(1L)).willReturn(List.of(
                SettlementEntry.scheduled(1L, 11L, "tx", "TOSS", 1L, 5000L, 125L, 0.025, 500L, 0.10, SETTLED),
                SettlementEntry.returnShippingScheduled(1L, 11L, "tx", "TOSS", 3000L, 75L, 0.025, SETTLED)));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 5000L, OrderItemStatus.ACTIVE)));
        given(orderService.getOrderDiscount(11L)).willReturn(withReturnCharge(5000L));
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));

        settlementService.reverseRefunds();

        List<SettlementEntry> saved = captureSaved(1);
        assertThat(saved.get(0).getEntryKind()).isEqualTo(SettlementEntryKind.RETURN_SHIPPING);
        assertThat(saved.get(0).getGrossAmount()).isEqualTo(2000L);   // 5000 − 3000, 통째로가 아니라 차액만
    }

    @Test
    @DisplayName("★ 셀러 귀책 과금 - gross=0·net=−과금액이라 Σgross(대사)를 건드리지 않는다")
    void run_sellerFaultCharge_doesNotTouchGross() {
        given(paymentService.getPaidPayments()).willReturn(List.of(payment(1L, 11L, 10000L)));
        given(settlementRepository.existsByPaymentIdAndEntryKindIn(anyLong(), any())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 10000L, OrderItemStatus.ACTIVE)));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));
        given(returnQueryService.getSellerFaultCharges(11L)).willReturn(java.util.Map.of(1L, 3000L));

        settlementService.run();

        List<SettlementEntry> saved = captureSaved(2);
        SettlementEntry charge = saved.stream()
                .filter(e -> e.getEntryKind() == SettlementEntryKind.FAULT_CHARGE).findFirst().orElseThrow();
        assertThat(charge.getSellerId()).isEqualTo(1L);
        assertThat(charge.getChargeAmount()).isEqualTo(3000L);
        assertThat(charge.getNetAmount()).isEqualTo(-3000L);   // 셀러 실수령에서 차감
        // ★ gross는 0 — 셀러↔플랫폼 내부 조정액이라 PG 원장에 대응 금액이 없다.
        //   gross에 실으면 Σgross가 결제액과 어긋나 대사가 즉시 AMOUNT_MISMATCH로 튄다.
        assertThat(charge.getGrossAmount()).isZero();
        assertThat(charge.getFee()).isZero();
        assertThat(saved.stream().mapToLong(SettlementEntry::getGrossAmount).sum()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("역분개 - 셀러 귀책 과금도 자기 target과만 비교한다(변화 없으면 멱등)")
    void reverse_faultChargeIdempotent() {
        given(paymentService.getSettlementReversalCandidates()).willReturn(List.of(payment(1L, 11L, 10000L)));
        given(settlementRepository.findByPaymentId(1L)).willReturn(List.of(
                SettlementEntry.scheduled(1L, 11L, "tx", "TOSS", 1L, 10000L, 250L, 0.025, 1000L, 0.10, SETTLED),
                SettlementEntry.faultCharge(1L, 11L, "tx", "TOSS", 1L, 3000L, SETTLED)));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 10000L, OrderItemStatus.ACTIVE)));
        given(returnQueryService.getSellerFaultCharges(11L)).willReturn(java.util.Map.of(1L, 3000L));

        settlementService.reverseRefunds();

        // 매출도 과금도 변화 없음 → 역분개 0건. 과금 엔트리가 셀러 매출 버킷에 섞였다면
        // gross 0·platformFeeRate 0.0이 집계를 오염시켜 엉뚱한 상계가 나갔을 것이다.
        verify(settlementRepository, org.mockito.Mockito.never()).save(any(SettlementEntry.class));
    }

    /**
     * 종류 축이 배타적이어야 하는 이유: 역분개는 종류별 버킷으로 target을 비교한다. 두 종류가 같은 술어에
     * 걸리면(예전 {@code shipping} boolean처럼) 회수비가 배송비 target에 수렴해 <b>조용히 사라진다</b>.
     */
    @Test
    @DisplayName("엔트리 종류는 배타적이다 - 각 팩토리가 정확히 하나의 kind를 만든다")
    void entryKindIsExclusive() {
        assertThat(SettlementEntry.shippingScheduled(1L, 11L, "tx", "TOSS", 3000L, 75L, 0.025, SETTLED).getEntryKind())
                .isEqualTo(SettlementEntryKind.SHIPPING);
        assertThat(SettlementEntry.returnShippingScheduled(1L, 11L, "tx", "TOSS", 3000L, 75L, 0.025, SETTLED)
                .getEntryKind()).isEqualTo(SettlementEntryKind.RETURN_SHIPPING);   // 회수비는 배송비가 아니다
        assertThat(SettlementEntry.scheduled(1L, 11L, "tx", "TOSS", 1L, 100L, 0L, 0.0, 0L, 0.0, SETTLED).getEntryKind())
                .isEqualTo(SettlementEntryKind.SALE);
        assertThat(SettlementEntry.faultCharge(1L, 11L, "tx", "TOSS", 1L, 3000L, SETTLED).getEntryKind())
                .isEqualTo(SettlementEntryKind.FAULT_CHARGE);

        // 매출 원장(정방향 정산이 만드는 것) 판정도 종류로만 갈린다 — 멱등 게이트가 이걸 본다
        assertThat(SettlementEntryKind.SALE.isSaleLedger()).isTrue();
        assertThat(SettlementEntryKind.SHIPPING.isSaleLedger()).isTrue();
        assertThat(SettlementEntryKind.RETURN_SHIPPING.isSaleLedger()).isFalse();
        assertThat(SettlementEntryKind.FAULT_CHARGE.isSaleLedger()).isFalse();
    }
}
