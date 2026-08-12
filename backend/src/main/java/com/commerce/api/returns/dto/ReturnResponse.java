package com.commerce.api.returns.dto;

import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnStatusHistory;
import com.commerce.api.returns.entity.ReturnType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 반품/교환 응답(#3) — 요청 상세 + 상태 타임라인.
 */
public record ReturnResponse(
        Long id,
        Long orderId,
        Long orderItemId,
        Long sellerId,
        Long memberId,
        ReturnType type,
        ReturnStatus status,
        String reason,
        com.commerce.api.global.common.CancelReason reasonCode,   // 구조화된 사유(#8, 없으면 null)
        com.commerce.api.global.common.CancelReason.Fault faultParty,   // 확정 귀책(검수에서 확정, 그 전 null)
        com.commerce.api.global.common.CancelReason.Fault effectiveFault,  // 돈 계산이 읽는 실효 귀책(미확정이면 사유에서 파생)
        int quantity,
        Long refundAmount,        // 검수확정 후 확정(RETURN), 그 전 null
        boolean restock,
        Long exchangeOptionId,    // 교환 대상 옵션(EXCHANGE)
        Long exchangeShipmentId,  // 교환 재출고 shipment(교환 완료 후)
        List<StatusHistoryResponse> statusHistory,
        LocalDateTime createdAt
) {
    public static ReturnResponse from(ReturnRequest r) {
        List<StatusHistoryResponse> history = r.getStatusHistory().stream()
                .map(StatusHistoryResponse::from)
                .toList();
        return new ReturnResponse(
                r.getId(), r.getOrderId(), r.getOrderItemId(), r.getSellerId(), r.getMemberId(),
                r.getType(), r.getStatus(), r.getReason(), r.getReasonCode(),
                r.getFaultParty(), r.effectiveFault(), r.getQuantity(),
                r.getRefundAmount(), r.isRestock(), r.getExchangeOptionId(), r.getExchangeShipmentId(),
                history, r.getCreatedAt());
    }

    public record StatusHistoryResponse(
            ReturnStatus fromStatus,
            ReturnStatus toStatus,
            Long changedBy,
            String memo,
            LocalDateTime createdAt
    ) {
        static StatusHistoryResponse from(ReturnStatusHistory h) {
            return new StatusHistoryResponse(
                    h.getFromStatus(), h.getToStatus(), h.getChangedBy(), h.getMemo(), h.getCreatedAt());
        }
    }
}
