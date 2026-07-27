package com.commerce.api.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.settlement.dto.PayoutCreateRequest;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.entity.Payout;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.repository.PayoutRepository;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PayoutService 단위 테스트 — 셀러 정산 항목 묶기/지급/조회.
 */
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock
    private PayoutRepository payoutRepository;
    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private SellerRepository sellerRepository;
    @InjectMocks
    private PayoutService payoutService;

    private static final LocalDate FROM = LocalDate.now();
    private static final LocalDate TO = LocalDate.now().plusDays(7);

    private SettlementEntry entry(long gross, long fee, long platformFee) {
        return SettlementEntry.scheduled(
                1L, 1L, "tx", "TOSS", 5L, gross, fee, 0.025, platformFee, 0.10, LocalDate.now().plusDays(2));
    }

    private Seller sellerWithId(Long id, String name) {
        Seller s = Seller.create(name, 0.10, null, null);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Test
    @DisplayName("생성 - 셀러 SCHEDULED 항목을 묶어 합계 스냅샷 + 원자적 조건부 UPDATE로 편입")
    void create_groupsAndSums() {
        List<SettlementEntry> entries = List.of(entry(10000, 250, 1000), entry(20000, 500, 2000));
        given(settlementRepository.findBySellerIdAndStatusAndPayoutIdIsNullAndSettledDateBetween(
                eq(5L), eq(SettlementStatus.SCHEDULED), any(), any())).willReturn(entries);
        given(payoutRepository.save(any(Payout.class))).willAnswer(inv -> {
            Payout p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 100L);
            return p;
        });
        given(settlementRepository.claimForPayout(eq(100L), anyList())).willReturn(2);   // 두 항목 모두 편입 성공
        given(sellerRepository.findById(5L)).willReturn(Optional.of(sellerWithId(5L, "UrbanSelect")));

        PayoutResponse response = payoutService.create(new PayoutCreateRequest(5L, FROM, TO));

        assertThat(response.totalGross()).isEqualTo(30000L);
        assertThat(response.totalFee()).isEqualTo(750L);
        assertThat(response.totalPlatformFee()).isEqualTo(3000L);
        assertThat(response.totalNet()).isEqualTo(26250L);   // 8750 + 17500
        assertThat(response.entryCount()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(response.sellerName()).isEqualTo("UrbanSelect");
        // 편입은 조건부 UPDATE(payout_id IS NULL 대상만)로 — 저장된 payoutId로 요청 항목을 원자적으로 잡는다.
        verify(settlementRepository).claimForPayout(eq(100L), anyList());
    }

    @Test
    @DisplayName("생성 실패 - 동시 편입 경합에 밀리면(claimed < 요청 항목 수) 409, 트랜잭션 롤백")
    void create_raceLosesClaim_conflict() {
        List<SettlementEntry> entries = List.of(entry(10000, 250, 1000), entry(20000, 500, 2000));
        given(settlementRepository.findBySellerIdAndStatusAndPayoutIdIsNullAndSettledDateBetween(
                eq(5L), eq(SettlementStatus.SCHEDULED), any(), any())).willReturn(entries);
        given(payoutRepository.save(any(Payout.class))).willAnswer(inv -> {
            Payout p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 100L);
            return p;
        });
        // 다른 create()가 먼저 한 항목을 잡아가 2개 중 1개만 편입됨 → 경합 감지
        given(settlementRepository.claimForPayout(eq(100L), anyList())).willReturn(1);

        assertThatThrownBy(() -> payoutService.create(new PayoutCreateRequest(5L, FROM, TO)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("동시에 다른 지급 요청");
    }

    @Test
    @DisplayName("생성 실패 - 대상 항목이 없으면 400, 저장 안 함")
    void create_empty() {
        given(settlementRepository.findBySellerIdAndStatusAndPayoutIdIsNullAndSettledDateBetween(
                eq(5L), eq(SettlementStatus.SCHEDULED), any(), any())).willReturn(List.of());

        assertThatThrownBy(() -> payoutService.create(new PayoutCreateRequest(5L, FROM, TO)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("지급할 정산 항목이 없습니다");
        verify(payoutRepository, never()).save(any());
    }

    @Test
    @DisplayName("지급 - 묶음 PAID + 묶인 항목들 PAID_OUT")
    void pay_marksPayoutAndEntries() {
        Payout payout = Payout.create(5L, FROM, TO, 30000, 750, 3000, 26250, 2);
        ReflectionTestUtils.setField(payout, "id", 100L);
        given(payoutRepository.findById(100L)).willReturn(Optional.of(payout));
        List<SettlementEntry> entries = List.of(entry(10000, 250, 1000), entry(20000, 500, 2000));
        entries.forEach(e -> e.assignPayout(100L));
        given(settlementRepository.findByPayoutId(100L)).willReturn(entries);
        given(sellerRepository.findById(5L)).willReturn(Optional.of(sellerWithId(5L, "UrbanSelect")));

        PayoutResponse response = payoutService.pay(100L);

        assertThat(response.status()).isEqualTo(PayoutStatus.PAID);
        assertThat(entries).allMatch(e -> e.getStatus() == SettlementStatus.PAID_OUT);
    }

    @Test
    @DisplayName("지급 실패 - 없는 묶음이면 404")
    void pay_notFound() {
        given(payoutRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> payoutService.pay(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("지급 묶음을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("목록 - 셀러+상태 필터면 findBySellerIdAndStatus")
    void getPayouts_filtered() {
        given(payoutRepository.findBySellerIdAndStatus(eq(5L), eq(PayoutStatus.PENDING), any()))
                .willReturn(Page.empty());

        payoutService.getPayouts(5L, PayoutStatus.PENDING, PageRequest.of(0, 20));

        verify(payoutRepository).findBySellerIdAndStatus(eq(5L), eq(PayoutStatus.PENDING), any());
    }
}
