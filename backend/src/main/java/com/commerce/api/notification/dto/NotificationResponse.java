package com.commerce.api.notification.dto;

import com.commerce.api.notification.entity.NotificationCategory;
import com.commerce.api.notification.entity.NotificationLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 알림 응답(#6) — 인박스 목록 항목. 수신자 식별자(recipientId)는 본인 것만 조회되므로 응답에 싣지 않는다.
 */
public record NotificationResponse(
        @Schema(description = "알림 id") Long id,
        @Schema(description = "이벤트 타입", example = "PAYMENT_COMPLETED") String type,
        @Schema(description = "성격(거래성/마케팅성)") NotificationCategory category,
        @Schema(description = "내용") String message,
        @Schema(description = "딥링크 대상(없으면 null)", example = "/orders/42") String link,
        @Schema(description = "읽음 여부") boolean read,
        @Schema(description = "생성 시각") LocalDateTime createdAt
) {
    public static NotificationResponse from(NotificationLog n) {
        return new NotificationResponse(
                n.getId(), n.getType(), n.getCategory(), n.getMessage(),
                n.getLink(), n.isRead(), n.getCreatedAt());
    }
}
