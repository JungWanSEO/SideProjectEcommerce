package com.commerce.api.seller.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 셀러 등록 요청(ADMIN).
 *
 * <p>commissionRate는 0 이상 1 미만의 비율(예: 0.1 = 10%). payoutAccount/businessNumber는 선택.
 */
public record SellerCreateRequest(
        @NotBlank(message = "셀러명은 필수입니다.")
        @Size(max = 50, message = "셀러명은 50자 이하여야 합니다.")
        String name,

        @NotNull(message = "판매수수료율은 필수입니다.")
        @DecimalMin(value = "0.0", message = "수수료율은 0 이상이어야 합니다.")
        @DecimalMax(value = "1.0", inclusive = false, message = "수수료율은 1 미만이어야 합니다.")
        Double commissionRate,

        @Size(max = 100, message = "정산계좌는 100자 이하여야 합니다.")
        String payoutAccount,

        @Size(max = 20, message = "사업자등록번호는 20자 이하여야 합니다.")
        String businessNumber
) {
}
