package com.commerce.api.returns.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.returns.dto.ReturnResponse;
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
}
