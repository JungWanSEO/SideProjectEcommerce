package com.commerce.api.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.order.dto.OrderResponse.OrderItemResponse;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.payment.dto.PaymentResponse;
import com.commerce.api.payment.entity.PaymentStatus;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.service.PaymentService;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.settlement.dto.SettlementResponse;
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

    private PaymentResponse paidPayment(Long id, Long orderId, long amount, String provider) {
        return new PaymentResponse(id, orderId, amount, PaymentStatus.PAID,
                "MOCK_CARD", provider, "MOCK-tx-" + id, LocalDateTime.now());
    }

    /** 주문 항목 — 정산은 sellerId·subtotal만 본다(나머지는 적당히 채움). */
    private OrderItemResponse item(Long sellerId, long subtotal) {
        return new OrderItemResponse(1L, 1L, null, sellerId, "P", "M", subtotal, 1, subtotal);
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
    @DisplayName("입금 확인 - SCHEDULED → PAID_OUT")
    void payout_marksPaidOut() {
        SettlementEntry entry = SettlementEntry.scheduled(
                1L, 11L, "MOCK-tx-1", "TOSS", 1L, 10000L, 250L, 0.025, 1000L, 0.10, LocalDate.now().plusDays(2));
        given(settlementRepository.findById(1L)).willReturn(Optional.of(entry));

        SettlementResponse response = settlementService.payout(1L);

        assertThat(response.status()).isEqualTo(SettlementStatus.PAID_OUT);
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
