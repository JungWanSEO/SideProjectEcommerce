package com.commerce.api.notification.entity;

import com.commerce.api.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 — 아웃박스 이벤트를 소비해 <b>특정 수신자의 인박스</b>에 남기는 한 건(#6, 인앱).
 *
 * <p><b>멱등 소비</b>: 발행이 at-least-once라 같은 이벤트가 두 번 올 수 있다. 멱등 키를
 * <b>(event_id, recipient_type, recipient_id) 복합 UNIQUE</b>로 둔다 — 하나의 이벤트가
 * <b>여러 수신자</b>에게 알림을 만들 수 있기 때문이다(예: 멀티셀러 주문 1건 = 셀러 N명에게 각각).
 * event_id 단독 UNIQUE였다면 두 번째 셀러 INSERT가 막혀버린다(초기 PAYMENT_COMPLETED는 1:1이라 안 걸렸던 함정).
 * DB의 복합 UNIQUE가 재도착·팬아웃 중복의 최후 방어선이다. (설계=docs/notification-matrix.md)
 */
@Getter
@Entity
@Table(name = "notification_log", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_event_recipient",
        columnNames = {"event_id", "recipient_type", "recipient_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;          // 소비한 아웃박스 이벤트 id (복합 멱등 키의 일부)

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 20)
    private RecipientType recipientType;   // 누구의 인박스인가 (BUYER/SELLER/ADMIN)

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;      // 수신자 식별자 — BUYER/ADMIN=memberId, SELLER=sellerId

    @Column(nullable = false, length = 50)
    private String type;           // 이벤트 타입 (예: "PAYMENT_COMPLETED")

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationCategory category;   // 거래성/마케팅성

    @Column(nullable = false, length = 255)
    private String message;        // 알림 내용

    @Column(length = 255)
    private String link;           // 딥링크 대상(선택, 예: "/orders/42")

    @Column(name = "read_at")
    private LocalDateTime readAt;  // 읽은 시각 — null이면 안읽음(벨 뱃지 카운트 기준)

    private NotificationLog(Long eventId, RecipientType recipientType, Long recipientId,
            String type, NotificationCategory category, String message, String link) {
        this.eventId = eventId;
        this.recipientType = recipientType;
        this.recipientId = recipientId;
        this.type = type;
        this.category = category;
        this.message = message;
        this.link = link;
    }

    public static NotificationLog of(Long eventId, RecipientType recipientType, Long recipientId,
            String type, NotificationCategory category, String message, String link) {
        return new NotificationLog(eventId, recipientType, recipientId, type, category, message, link);
    }

    /** 읽음 처리 — 멱등(이미 읽었으면 시각 보존). */
    public void markRead() {
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }

    public boolean isRead() {
        return this.readAt != null;
    }
}
