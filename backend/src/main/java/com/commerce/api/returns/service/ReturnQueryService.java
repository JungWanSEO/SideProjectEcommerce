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
