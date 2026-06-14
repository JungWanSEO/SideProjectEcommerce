package com.commerce.api.seller.dto;

import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.entity.SellerStatus;
import java.time.LocalDateTime;

/**
 * 셀러 응답.
 */
public record SellerResponse(
        Long id,
        String name,
        double commissionRate,
        SellerStatus status,
        String payoutAccount,
        String businessNumber,
        LocalDateTime createdAt
) {

    public static SellerResponse from(Seller seller) {
        return new SellerResponse(
                seller.getId(),
                seller.getName(),
                seller.getCommissionRate(),
                seller.getStatus(),
                seller.getPayoutAccount(),
                seller.getBusinessNumber(),
                seller.getCreatedAt());
    }
}
