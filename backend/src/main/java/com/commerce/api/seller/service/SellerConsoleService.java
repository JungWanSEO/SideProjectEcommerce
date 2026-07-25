package com.commerce.api.seller.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.dto.OrderSearchCondition;
import com.commerce.api.order.dto.OrderSummaryResponse;
import com.commerce.api.order.dto.SellerShipmentResponse;
import com.commerce.api.order.entity.ShipmentStatus;
import com.commerce.api.order.service.OrderService;
import com.commerce.api.order.service.ShipmentService;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.dto.ReturnStatusUpdateRequest;
import com.commerce.api.returns.service.ReturnQueryService;
import com.commerce.api.returns.service.ReturnService;
import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.settlement.dto.PayoutResponse;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementResponse;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.PayoutStatus;
import com.commerce.api.settlement.entity.SettlementStatus;
import com.commerce.api.settlement.service.PayoutService;
import com.commerce.api.settlement.service.SettlementService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 콘솔 서비스 — 로그인한 셀러 운영자가 <b>자기 셀러의</b> 정보·정산만 보도록 스코핑한다.
 *
 * <p>회원(Member.sellerId)으로 셀러를 도출해 기존 정산 서비스를 그대로 재사용한다 — 셀러는
 * 자신의 sellerId만 조회하므로 남의 정산은 구조적으로 볼 수 없다(IDOR 차단). 셀러 계정이 아니면 403.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerConsoleService {

    private final MemberRepository memberRepository;
    private final SellerService sellerService;
    private final SettlementService settlementService;
    private final PayoutService payoutService;
    private final OrderService orderService;   // "내 주문"(내 셀러 상품이 든 주문) 조회 — 셀러 스코프는 서비스가 강제
    private final ShipmentService shipmentService;   // 내 shipment 출고 전진(#1 c안) — 소유권은 워커가 검증
    private final ReturnService returnService;         // 내 반품 처리(#3) — 소유권은 서비스가 검증
    private final ReturnQueryService returnQueryService;   // 내 반품 목록(셀러 스코프)

    /** 내 셀러 정보. */
    public SellerResponse getMySeller(Long memberId) {
        return sellerService.getSeller(requireSellerId(memberId));
    }

    /**
     * 내 주문 — 이 셀러의 상품이 하나라도 든 주문 목록. 셀러가 "무엇을 포장해 보낼지"를 보는 화면.
     * 셀러 스코프는 {@link OrderService#searchSellerOrders}가 로그인 셀러로 강제(요청값 무시)한다 — 남의 셀러 주문 차단.
     */
    public PageResponse<OrderSummaryResponse> getMyOrders(
            Long memberId, OrderSearchCondition condition, Pageable pageable) {
        return orderService.searchSellerOrders(requireSellerId(memberId), condition, pageable);
    }

    /** 내 정산 항목(상태·기간 필터). */
    public PageResponse<SettlementResponse> getMySettlements(
            Long memberId, SettlementStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        Long sellerId = requireSellerId(memberId);
        return settlementService.getSettlements(
                new SettlementSearchCondition(sellerId, status, from, to), pageable);
    }

    /** 내 정산서(셀러별 집계 — 본인 1건). */
    public List<SellerSettlementSummary> getMySummary(
            Long memberId, SettlementStatus status, LocalDate from, LocalDate to) {
        Long sellerId = requireSellerId(memberId);
        return settlementService.getSellerSummary(
                new SettlementSearchCondition(sellerId, status, from, to));
    }

    /** 내 지급 묶음 목록(상태 필터). */
    public PageResponse<PayoutResponse> getMyPayouts(Long memberId, PayoutStatus status, Pageable pageable) {
        Long sellerId = requireSellerId(memberId);
        return payoutService.getPayouts(sellerId, status, pageable);
    }

    /**
     * 내 shipment 출고 전진(#1 c안) — 로그인 셀러가 자기 shipment를 PAID→SHIPPING→DELIVERED로 보낸다.
     * 소유권(그 shipment가 내 셀러 것인지·플랫폼 null 버킷 아닌지)은 {@link ShipmentService}의 워커가
     * 트랜잭션 안에서 검증한다(아니면 403). 잘못된 전이면 409.
     *
     * <p>{@link Propagation#NOT_SUPPORTED}로 클래스의 read-only 트랜잭션 밖에서 실행한다 — 안 그러면 read-only
     * 컨텍스트에 쓰기가 막히고, ShipmentService의 낙관락 재시도(새 트랜잭션)도 깨진다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SellerShipmentResponse advanceMyShipment(Long memberId, Long shipmentId, ShipmentStatus next,
            String courier, String trackingNumber) {
        Long sellerId = requireSellerId(memberId);
        return shipmentService.advanceForSeller(shipmentId, sellerId, next, memberId, courier, trackingNumber);
    }

    /** 내 반품/교환 목록(셀러 스코프). */
    public PageResponse<ReturnResponse> getMyReturns(Long memberId, Pageable pageable) {
        return returnQueryService.getSellerReturns(requireSellerId(memberId), pageable);
    }

    /**
     * 내 반품/교환 처리(#3) — 승인/거부/수거/검수. 소유권(그 반품이 내 셀러 것인지)은 {@link ReturnService}가
     * 트랜잭션 안에서 검증(아니면 403). {@link Propagation#NOT_SUPPORTED}로 read-only 클래스 tx 밖에서 실행
     * (쓰기·비관락 직렬화 보존, advanceMyShipment와 동일).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReturnResponse advanceMyReturn(Long memberId, Long returnId, ReturnStatusUpdateRequest request) {
        Long sellerId = requireSellerId(memberId);
        return returnService.advanceForSeller(returnId, sellerId, request, memberId);
    }

    /** 로그인 회원의 셀러 ID — 셀러 계정(sellerId 보유)이 아니면 403. */
    private Long requireSellerId(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."));
        Long sellerId = member.getSellerId();
        if (sellerId == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "셀러 계정이 아닙니다.");
        }
        return sellerId;
    }
}
