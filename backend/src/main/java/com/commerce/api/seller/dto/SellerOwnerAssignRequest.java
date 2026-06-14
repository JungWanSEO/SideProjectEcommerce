package com.commerce.api.seller.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 셀러 운영자(로그인 회원) 지정 요청(ADMIN).
 * 지정된 회원은 role=SELLER가 되고 이 셀러에 연결된다(다음 로그인부터 셀러 콘솔 사용).
 */
public record SellerOwnerAssignRequest(
        @NotNull(message = "memberId는 필수입니다.")
        Long memberId
) {
}
