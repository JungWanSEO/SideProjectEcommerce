package com.commerce.api.settlement.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 지급 묶음 생성 요청(ADMIN) — 셀러의 SCHEDULED·미지급 정산 항목을 기간으로 묶는다.
 */
public record PayoutCreateRequest(
        @NotNull(message = "sellerId는 필수입니다.") Long sellerId,
        @NotNull(message = "from(정산일 시작)은 필수입니다.") LocalDate from,
        @NotNull(message = "to(정산일 끝)은 필수입니다.") LocalDate to
) {
}
