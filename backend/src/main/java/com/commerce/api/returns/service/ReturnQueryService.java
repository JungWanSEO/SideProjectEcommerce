package com.commerce.api.returns.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.repository.ReturnRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품/교환 조회(#3, 읽기 전용) — 구매자 내 목록·셀러 스코프 목록. 쓰기 경로(ReturnService)와 분리.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReturnQueryService {

    private final ReturnRequestRepository returnRequestRepository;

    /**
     * 주문의 <b>셀러 귀책 회수비</b>를 셀러별로 합산해 돌려준다(#8 후속 P4) — 정산이 읽는 경계 메서드.
     *
     * <p>settlement → returns 의존을 <b>서비스 + 원시 Map</b>으로만 노출한다(엔티티·리포지토리를 직접 넘기지
     * 않음). settlement이 이미 payment·order를 같은 방식으로 읽고 있어 방향과 형태가 일관된다.
     *
     * @return 셀러ID → 그 셀러가 부담할 회수비 합. 없으면 빈 맵.
     */
    public java.util.Map<Long, Long> getSellerFaultCharges(Long orderId) {
        java.util.Map<Long, Long> result = new java.util.LinkedHashMap<>();
        for (Object[] row : returnRequestRepository.sumSellerFaultChargesByOrderId(orderId)) {
            result.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    /** 구매자 본인의 반품/교환 목록. */
    public PageResponse<ReturnResponse> getMyReturns(Long memberId, Pageable pageable) {
        return PageResponse.from(returnRequestRepository.findByMemberId(memberId, pageable).map(ReturnResponse::from));
    }

    /** 셀러 스코프 반품/교환 목록(sellerId는 호출자가 requireSellerId로 강제). */
    public PageResponse<ReturnResponse> getSellerReturns(Long sellerId, Pageable pageable) {
        return PageResponse.from(returnRequestRepository.findBySellerId(sellerId, pageable).map(ReturnResponse::from));
    }

    /**
     * 어드민 반품/교환 검색 — <b>전체 스코프</b>(운영자는 셀러를 넘나들며 대행 처리한다).
     *
     * <p>인가는 경로(SecurityConfig `/api/returns/admin` = ADMIN)가 담당한다. 구매자·셀러 목록이 소유로
     * 좁히는 것과 달리 여기선 좁히지 않으므로, <b>경로를 완화하면 곧 전체 노출</b>이라는 점이 이 메서드의 유일한 위험이다.
     */
    public PageResponse<ReturnResponse> searchForAdmin(ReturnStatus status, ReturnType type, Long sellerId,
            Pageable pageable) {
        return PageResponse.from(
                returnRequestRepository.searchForAdmin(status, type, sellerId, pageable).map(ReturnResponse::from));
    }
}
