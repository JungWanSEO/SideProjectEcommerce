package com.commerce.api.seller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.entity.SellerStatus;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.service.PayoutService;
import com.commerce.api.settlement.service.SettlementService;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SellerConsoleService 단위 테스트 — 로그인 회원→셀러 스코핑(자기 것만), 셀러 아니면 403.
 */
@ExtendWith(MockitoExtension.class)
class SellerConsoleServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private SellerService sellerService;
    @Mock
    private SettlementService settlementService;
    @Mock
    private PayoutService payoutService;
    @Mock
    private com.commerce.api.order.service.OrderService orderService;
    @Mock
    private com.commerce.api.notification.service.NotificationService notificationService;
    @InjectMocks
    private SellerConsoleService sellerConsoleService;

    private Member memberWithSeller(Long id, Long sellerId) {
        Member m = Member.builder()
                .email("s@c.com").password("x").nickname("운영자").role(Role.USER).build();
        ReflectionTestUtils.setField(m, "id", id);
        if (sellerId != null) {
            m.assignAsSeller(sellerId);   // role=SELLER + sellerId
        }
        return m;
    }

    @Test
    @DisplayName("내 셀러 조회 - 회원의 sellerId로 셀러 정보 위임")
    void getMySeller_resolvesAndDelegates() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithSeller(1L, 5L)));
        given(sellerService.getSeller(5L)).willReturn(
                new SellerResponse(5L, "UrbanSelect", 0.10, SellerStatus.ACTIVE, null, null, null));

        SellerResponse response = sellerConsoleService.getMySeller(1L);

        assertThat(response.id()).isEqualTo(5L);
        verify(sellerService).getSeller(5L);
    }

    @Test
    @DisplayName("내 알림 - 내 sellerId로 스코핑해 셀러 인박스 조회에 위임")
    void getMyNotifications_scopedToMySeller() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithSeller(1L, 5L)));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);

        sellerConsoleService.getMyNotifications(1L, false, pageable);

        verify(notificationService).getSellerNotifications(5L, false, pageable);
    }

    @Test
    @DisplayName("셀러 계정이 아니면 403 (sellerId 없음)")
    void notSeller_forbidden() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithSeller(1L, null)));

        assertThatThrownBy(() -> sellerConsoleService.getMySeller(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("셀러 계정이 아닙니다");
    }

    @Test
    @DisplayName("내 주문 - 내 sellerId로 스코핑해 주문 검색에 위임(남의 셀러 주문 차단)")
    void getMyOrders_scopedToMySeller() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithSeller(1L, 5L)));
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        var condition = new com.commerce.api.order.dto.OrderSearchCondition(
                null, null, null, null, null, null, null, null);

        sellerConsoleService.getMyOrders(1L, condition, pageable);

        verify(orderService).searchSellerOrders(
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(condition),
                org.mockito.ArgumentMatchers.eq(pageable));
    }

    @Test
    @DisplayName("내 정산서 - 내 sellerId로 스코핑해 집계 위임")
    void getMySummary_scopedToMySeller() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithSeller(1L, 5L)));
        given(settlementService.getSellerSummary(org.mockito.ArgumentMatchers.any())).willReturn(List.of());

        sellerConsoleService.getMySummary(1L, null, null, null);

        ArgumentCaptor<SettlementSearchCondition> captor =
                ArgumentCaptor.forClass(SettlementSearchCondition.class);
        verify(settlementService).getSellerSummary(captor.capture());
        assertThat(captor.getValue().sellerId()).isEqualTo(5L);   // 남의 정산은 구조적으로 못 봄
    }

    @Test
    @DisplayName("내 지급 내역 - 내 sellerId로 스코핑해 위임")
    void getMyPayouts_scoped() {
        given(memberRepository.findById(1L)).willReturn(Optional.of(memberWithSeller(1L, 5L)));
        given(payoutService.getPayouts(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        sellerConsoleService.getMyPayouts(1L, null, PageRequest.of(0, 20));

        verify(payoutService).getPayouts(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("회원 없으면 401")
    void memberNotFound() {
        given(memberRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> sellerConsoleService.getMySeller(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("인증이 필요합니다");
    }
}
