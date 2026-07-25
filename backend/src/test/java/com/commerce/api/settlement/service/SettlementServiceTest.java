package com.commerce.api.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
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
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.dto.SettlementReverseResponse;
import com.commerce.api.settlement.dto.SettlementRunResponse;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementStatus;
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
 * SettlementService 단위 테스트 — 셀러별 정산 배치(매출 분해·PG수수료 안분·플랫폼수수료·실수령) / 입금 확인.
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private PaymentService paymentService;
    @Mock
    private PaymentGatewayRouter paymentGatewayRouter;
    @Mock
    private OrderService orderService;
    @Mock
    private SellerRepository sellerRepository;

    @InjectMocks
    private SettlementService settlementService;

    /** 기본은 할인 없는 주문 — 할인 테스트만 getOrderDiscount를 따로 스텁한다(lenient: 미사용 테스트 무시). */
    @org.junit.jupiter.api.BeforeEach
    void defaultNoDiscount() {
        lenient().when(orderService.getOrderDiscount(anyLong())).thenReturn(OrderDiscountInfo.none());
    }

    private PaymentResponse paidPayment(Long id, Long orderId, long amount, String provider) {
        return new PaymentResponse(id, orderId, amount, PaymentStatus.PAID,
                "MOCK_CARD", provider, "MOCK-tx-" + id, LocalDateTime.now());
    }

    /** 주문 항목 — 정산은 sellerId·subtotal·discountShare·status만 본다. 기본 ACTIVE·할인 0. */
    private OrderItemResponse item(Long sellerId, long subtotal) {
        return item(sellerId, subtotal, 0L, OrderItemStatus.ACTIVE);
    }

    private OrderItemResponse item(Long sellerId, long subtotal, OrderItemStatus status) {
        return item(sellerId, subtotal, 0L, status);
    }

    /** 항목별 안분 할인(discountShare)을 지정 — 할인 정산 테스트용(ACTIVE). */
    private OrderItemResponse itemWithDiscount(Long sellerId, long subtotal, long discountShare) {
        return item(sellerId, subtotal, discountShare, OrderItemStatus.ACTIVE);
    }

    private OrderItemResponse item(Long sellerId, long subtotal, long discountShare, OrderItemStatus status) {
        return new OrderItemResponse(1L, 1L, 1L, null, sellerId, "P", "M", subtotal, 1, subtotal, discountShare, status);
    }

    /** 이미 정산된 항목(역분개 테스트용) — platformFeeRate 0.10 스냅샷. */
    private SettlementEntry settled(Long sellerId, long gross, long fee, long platformFee) {
        return SettlementEntry.scheduled(
                1L, 11L, "tx", "TOSS", sellerId, gross, fee, 0.025, platformFee, 0.10, LocalDate.now().plusDays(2));
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

    @Test
    @DisplayName("정산 - 단일 셀러: 매출에서 PG수수료(2.5%)+플랫폼수수료(10%)를 떼고 실수령 계산")
    void run_singleSeller() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 10000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(anyLong())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 10000L)));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));

        SettlementRunResponse summary = settlementService.run();

        assertThat(summary.createdCount()).isEqualTo(1);
        assertThat(summary.totalGrossAmount()).isEqualTo(10000L);
        assertThat(summary.totalFee()).isEqualTo(250L);            // PG 2.5%
        assertThat(summary.totalPlatformFee()).isEqualTo(1000L);   // 플랫폼 10%
        assertThat(summary.totalNetAmount()).isEqualTo(8750L);     // 10000 - 250 - 1000

        SettlementEntry entry = captureSaved(1).get(0);
        assertThat(entry.getSellerId()).isEqualTo(1L);
        assertThat(entry.getProvider()).isEqualTo("TOSS");
        assertThat(entry.getGrossAmount()).isEqualTo(10000L);
        assertThat(entry.getFee()).isEqualTo(250L);
        assertThat(entry.getFeeRate()).isEqualTo(0.025);
        assertThat(entry.getPlatformFee()).isEqualTo(1000L);
        assertThat(entry.getPlatformFeeRate()).isEqualTo(0.10);
        assertThat(entry.getNetAmount()).isEqualTo(8750L);
        assertThat(entry.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        assertThat(entry.getSettledDate()).isEqualTo(LocalDate.now().plusDays(2));

        assertThat(summary.bySeller()).hasSize(1);
        assertThat(summary.bySeller().get(0).sellerId()).isEqualTo(1L);
        assertThat(summary.bySeller().get(0).netAmount()).isEqualTo(8750L);
    }

    @Test
    @DisplayName("정산 - 멀티 셀러 주문: 매출 비례로 PG수수료 안분, 셀러별 요율로 플랫폼수수료")
    void run_multiSeller() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 10000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(anyLong())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);   // pgFee 총 250
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(1L, 6000L), item(2L, 4000L)));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));
        given(sellerRepository.findById(2L)).willReturn(Optional.of(sellerWithRate(2L, 0.05)));

        SettlementRunResponse summary = settlementService.run();

        // 2 항목, 합계: gross 10000 / pgFee 250(=150+100) / platformFee 800(=600+200) / net 8950
        assertThat(summary.createdCount()).isEqualTo(2);
        assertThat(summary.totalGrossAmount()).isEqualTo(10000L);
        assertThat(summary.totalFee()).isEqualTo(250L);
        assertThat(summary.totalPlatformFee()).isEqualTo(800L);
        assertThat(summary.totalNetAmount()).isEqualTo(8950L);

        List<SettlementEntry> saved = captureSaved(2);
        SettlementEntry s1 = saved.stream().filter(e -> e.getSellerId() == 1L).findFirst().orElseThrow();
        SettlementEntry s2 = saved.stream().filter(e -> e.getSellerId() == 2L).findFirst().orElseThrow();
        // 셀러1: gross 6000, pgFee 150(250×0.6), platformFee 600(6000×10%), net 5250
        assertThat(s1.getFee()).isEqualTo(150L);
        assertThat(s1.getPlatformFee()).isEqualTo(600L);
        assertThat(s1.getNetAmount()).isEqualTo(5250L);
        // 셀러2: gross 4000, pgFee 100(250×0.4), platformFee 200(4000×5%), net 3700
        assertThat(s2.getFee()).isEqualTo(100L);
        assertThat(s2.getPlatformFee()).isEqualTo(200L);
        assertThat(s2.getNetAmount()).isEqualTo(3700L);
    }

    @Test
    @DisplayName("정산 - PG수수료 안분 반올림 잔차는 매출 최대 셀러에 몰아 합을 보존(Σfee=pgFeeTotal)")
    void run_pgFeeResidualToLargestSeller() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 10000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(anyLong())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);   // pgFee 총 250
        // 3333/3333/3334 → 비례 안분 시 83/83/83=249, 잔차 1을 매출 최대(3334)인 셀러3에 → 84
        given(orderService.getOrderItems(11L))
                .willReturn(List.of(item(1L, 3333L), item(2L, 3333L), item(3L, 3334L)));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.0)));
        given(sellerRepository.findById(2L)).willReturn(Optional.of(sellerWithRate(2L, 0.0)));
        given(sellerRepository.findById(3L)).willReturn(Optional.of(sellerWithRate(3L, 0.0)));

        SettlementRunResponse summary = settlementService.run();

        assertThat(summary.totalFee()).isEqualTo(250L);   // 잔차 보정으로 합 보존
        List<SettlementEntry> saved = captureSaved(3);
        SettlementEntry s3 = saved.stream().filter(e -> e.getSellerId() == 3L).findFirst().orElseThrow();
        assertThat(s3.getFee()).isEqualTo(84L);           // 83 + 잔차 1
    }

    @Test
    @DisplayName("정산 - 미귀속(sellerId=null) 항목: 플랫폼수수료 0, 셀러 조회 안 함")
    void run_nullSellerNoPlatformFee() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 10000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(anyLong())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        given(orderService.getOrderItems(11L)).willReturn(List.of(item(null, 10000L)));

        SettlementRunResponse summary = settlementService.run();

        SettlementEntry entry = captureSaved(1).get(0);
        assertThat(entry.getSellerId()).isNull();
        assertThat(entry.getFee()).isEqualTo(250L);
        assertThat(entry.getPlatformFee()).isZero();
        assertThat(entry.getNetAmount()).isEqualTo(9750L);   // 10000 - 250 - 0
        verify(sellerRepository, never()).findById(any());
    }

    @Test
    @DisplayName("정산 - 이미 정산된 결제는 건너뜀(멱등)")
    void run_idempotentSkip() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 10000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(1L)).willReturn(true);

        SettlementRunResponse summary = settlementService.run();

        assertThat(summary.createdCount()).isZero();
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("정산 - 취소(부분환불)된 항목은 제외하고 활성 항목만 정산")
    void run_excludesCancelledItems() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 30000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(anyLong())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        // 같은 셀러 두 항목: 하나는 취소(부분환불)
        given(orderService.getOrderItems(11L)).willReturn(List.of(
                item(1L, 10000L, OrderItemStatus.ACTIVE), item(1L, 20000L, OrderItemStatus.CANCELLED)));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));

        settlementService.run();

        SettlementEntry entry = captureSaved(1).get(0);
        assertThat(entry.getGrossAmount()).isEqualTo(10000L);   // 취소분 20000 제외
        assertThat(entry.getFee()).isEqualTo(250L);             // 활성 매출 기준 PG수수료
    }

    @Test
    @DisplayName("정산 - 플랫폼 와이드·플랫폼 부담 쿠폰: 할인을 매출 비례로 안분, gross=할인 후 몫, net에 할인 환원(셀러 무손실)")
    void run_platformWidePlatformFundedDiscount() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 9000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(anyLong())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        // 주문이 할인을 항목별로 안분(매출 비례): 600(s1)·400(s2). 정산은 활성 항목의 share를 합산. 플랫폼 부담.
        given(orderService.getOrderItems(11L))
                .willReturn(List.of(itemWithDiscount(1L, 6000L, 600L), itemWithDiscount(2L, 4000L, 400L)));
        given(orderService.getOrderDiscount(11L)).willReturn(new OrderDiscountInfo(1000L, "PLATFORM", null));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));
        given(sellerRepository.findById(2L)).willReturn(Optional.of(sellerWithRate(2L, 0.05)));

        SettlementRunResponse summary = settlementService.run();

        List<SettlementEntry> saved = captureSaved(2);
        SettlementEntry s1 = saved.stream().filter(e -> e.getSellerId() == 1L).findFirst().orElseThrow();
        SettlementEntry s2 = saved.stream().filter(e -> e.getSellerId() == 2L).findFirst().orElseThrow();
        // 할인 안분: 1000 × 6000/10000 = 600(s1), 400(s2). gross = 매출 - 안분 할인.
        assertThat(s1.getDiscountAmount()).isEqualTo(600L);
        assertThat(s1.getGrossAmount()).isEqualTo(5400L);
        assertThat(s1.getDiscountFundedBy()).isEqualTo("PLATFORM");
        // s1 net = 5400 - pgFee(135) - platformFee(540) + 환원(600) = 5325
        assertThat(s1.getFee()).isEqualTo(135L);
        assertThat(s1.getPlatformFee()).isEqualTo(540L);
        assertThat(s1.getNetAmount()).isEqualTo(5325L);
        assertThat(s2.getDiscountAmount()).isEqualTo(400L);
        assertThat(s2.getGrossAmount()).isEqualTo(3600L);
        assertThat(s2.getNetAmount()).isEqualTo(3730L);   // 3600 - 90 - 180 + 400
        // 대사 불변식: Σgross = payable(9000)
        assertThat(summary.totalGrossAmount()).isEqualTo(9000L);
        assertThat(summary.totalDiscount()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("정산 - 셀러 한정·셀러 부담 쿠폰: 할인은 그 셀러에 전액, 그 셀러 net이 줄어 셀러가 부담(다른 셀러 불변)")
    void run_sellerScopedSellerFundedDiscount() {
        given(paymentService.getPaidPayments()).willReturn(List.of(paidPayment(1L, 11L, 9000L, "TOSS")));
        given(settlementRepository.existsByPaymentId(anyLong())).willReturn(false);
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        // 셀러1 한정: 셀러1 항목에 전액 안분(1000), 셀러2는 0. 셀러 부담.
        given(orderService.getOrderItems(11L))
                .willReturn(List.of(itemWithDiscount(1L, 6000L, 1000L), item(2L, 4000L)));
        given(orderService.getOrderDiscount(11L)).willReturn(new OrderDiscountInfo(1000L, "SELLER", 1L));
        given(sellerRepository.findById(1L)).willReturn(Optional.of(sellerWithRate(1L, 0.10)));
        given(sellerRepository.findById(2L)).willReturn(Optional.of(sellerWithRate(2L, 0.05)));

        SettlementRunResponse summary = settlementService.run();

        List<SettlementEntry> saved = captureSaved(2);
        SettlementEntry s1 = saved.stream().filter(e -> e.getSellerId() == 1L).findFirst().orElseThrow();
        SettlementEntry s2 = saved.stream().filter(e -> e.getSellerId() == 2L).findFirst().orElseThrow();
        // 셀러1: 전액 할인 1000 → gross 5000, 환원 없음(셀러 부담)
        assertThat(s1.getDiscountAmount()).isEqualTo(1000L);
        assertThat(s1.getGrossAmount()).isEqualTo(5000L);
        assertThat(s1.getDiscountFundedBy()).isEqualTo("SELLER");
        // s1 net = 5000 - pgFee(125) - platformFee(500) + 0 = 4375
        assertThat(s1.getFee()).isEqualTo(125L);
        assertThat(s1.getPlatformFee()).isEqualTo(500L);
        assertThat(s1.getNetAmount()).isEqualTo(4375L);
        // 셀러2: 할인 없음(불변)
        assertThat(s2.getDiscountAmount()).isZero();
        assertThat(s2.getGrossAmount()).isEqualTo(4000L);
        assertThat(s2.getNetAmount()).isEqualTo(3700L);   // 4000 - 100 - 200
        assertThat(summary.totalGrossAmount()).isEqualTo(9000L);   // 대사 = payable
    }

    @Test
    @DisplayName("환불 상계 - 정산 후 항목 취소되면 음수 역분개 항목 생성")
    void reverseRefunds_offsetsCancelledSeller() {
        given(paymentService.getSettlementReversalCandidates()).willReturn(List.of(paidPayment(1L, 11L, 30000L, "TOSS")));
        // 기존 정산: 셀러1(10000) + 셀러2(20000)
        given(settlementRepository.findByPaymentId(1L)).willReturn(List.of(
                settled(1L, 10000L, 250L, 1000L), settled(2L, 20000L, 500L, 2000L)));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        // 현재 활성: 셀러1만(셀러2 항목 취소됨)
        given(orderService.getOrderItems(11L)).willReturn(List.of(
                item(1L, 10000L, OrderItemStatus.ACTIVE), item(2L, 20000L, OrderItemStatus.CANCELLED)));
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));

        SettlementReverseResponse result = settlementService.reverseRefunds();

        assertThat(result.reversedCount()).isEqualTo(1);   // 셀러2만 상계(셀러1은 변화 없음)
        SettlementEntry rev = captureSaved(1).get(0);
        assertThat(rev.getSellerId()).isEqualTo(2L);
        assertThat(rev.getGrossAmount()).isEqualTo(-20000L);   // 음수 역분개
        assertThat(rev.getFee()).isEqualTo(-500L);
        assertThat(rev.getPlatformFee()).isEqualTo(-2000L);
        assertThat(rev.getNetAmount()).isEqualTo(-17500L);
    }

    @Test
    @DisplayName("환불 상계(할인 주문) - 할인 항목 취소 시 할인·net 환원까지 음수로 상계(Step 2b)")
    void reverseRefunds_discountedOrder() {
        // 원 정산(플랫폼 부담 쿠폰): s1 reduced5400·fee135·plat540·disc600·net5325 / s2 reduced3600·fee90·plat180·disc400·net3730
        SettlementEntry s1 = SettlementEntry.scheduled(1L, 11L, "tx", "TOSS", 1L,
                5400, 135, 0.025, 540, 0.10, 600, "PLATFORM", LocalDate.now().plusDays(2));
        SettlementEntry s2 = SettlementEntry.scheduled(1L, 11L, "tx", "TOSS", 2L,
                3600, 90, 0.025, 180, 0.05, 400, "PLATFORM", LocalDate.now().plusDays(2));
        given(paymentService.getSettlementReversalCandidates()).willReturn(List.of(paidPayment(1L, 11L, 9000L, "TOSS")));
        given(settlementRepository.findByPaymentId(1L)).willReturn(List.of(s1, s2));
        given(paymentGatewayRouter.feeRateOf("TOSS")).willReturn(0.025);
        // 셀러2 항목 취소 → 활성=셀러1만(gross6000·share600). 취소된 셀러2 항목(share400)은 정산에서 제외.
        given(orderService.getOrderItems(11L)).willReturn(List.of(
                itemWithDiscount(1L, 6000L, 600L),
                item(2L, 4000L, 400L, OrderItemStatus.CANCELLED)));
        given(orderService.getOrderDiscount(11L)).willReturn(new OrderDiscountInfo(1000L, "PLATFORM", null));
        given(settlementRepository.save(any(SettlementEntry.class))).willAnswer(inv -> inv.getArgument(0));

        SettlementReverseResponse result = settlementService.reverseRefunds();

        assertThat(result.reversedCount()).isEqualTo(1);   // 셀러1은 불변(diff 0), 셀러2만 상계
        SettlementEntry rev = captureSaved(1).get(0);
        assertThat(rev.getSellerId()).isEqualTo(2L);
        assertThat(rev.getGrossAmount()).isEqualTo(-3600L);
        assertThat(rev.getFee()).isEqualTo(-90L);
        assertThat(rev.getPlatformFee()).isEqualTo(-180L);
        assertThat(rev.getDiscountAmount()).isEqualTo(-400L);          // 할인도 음수 상계
        assertThat(rev.getDiscountFundedBy()).isEqualTo("PLATFORM");
        assertThat(rev.getNetAmount()).isEqualTo(-3730L);             // 환원(subsidy)까지 선형 상계 = −원net
    }

    @Test
    @DisplayName("입금 확인 - SCHEDULED → PAID_OUT")
    void payout_marksPaidOut() {
        SettlementEntry entry = SettlementEntry.scheduled(
                1L, 11L, "MOCK-tx-1", "TOSS", 1L, 10000L, 250L, 0.025, 1000L, 0.10, LocalDate.now().plusDays(2));
        given(settlementRepository.findById(1L)).willReturn(Optional.of(entry));

        SettlementResponse response = settlementService.payout(1L);

        assertThat(response.status()).isEqualTo(SettlementStatus.PAID_OUT);
    }

    @Test
    @DisplayName("입금 확인 실패 - 지급 묶음에 포함된 항목이면 409(묶음으로 지급)")
    void payout_inBatch() {
        SettlementEntry entry = SettlementEntry.scheduled(
                1L, 11L, "MOCK-tx-1", "TOSS", 1L, 10000L, 250L, 0.025, 1000L, 0.10, LocalDate.now().plusDays(2));
        entry.assignPayout(7L);   // 이미 지급 묶음에 편입됨
        given(settlementRepository.findById(1L)).willReturn(Optional.of(entry));

        assertThatThrownBy(() -> settlementService.payout(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("지급 묶음에 포함된 항목");
    }

    @Test
    @DisplayName("입금 확인 - 없는 정산 항목이면 404")
    void payout_notFound() {
        given(settlementRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.payout(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("정산 항목을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("입금 확인 - 이미 PAID_OUT이면 409(상태머신 가드)")
    void payout_alreadyPaidOut() {
        SettlementEntry entry = SettlementEntry.scheduled(
                1L, 11L, "MOCK-tx-1", "TOSS", 1L, 10000L, 250L, 0.025, 1000L, 0.10, LocalDate.now().plusDays(2));
        entry.markPaidOut();
        given(settlementRepository.findById(1L)).willReturn(Optional.of(entry));

        assertThatThrownBy(() -> settlementService.payout(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("정산 상태 전이가 올바르지 않습니다");
    }
}
