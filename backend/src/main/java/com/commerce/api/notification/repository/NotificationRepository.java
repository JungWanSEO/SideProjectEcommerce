package com.commerce.api.notification.repository;

import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * 이 이벤트로 <b>이 수신자</b>에게 이미 알림을 만들었는지 — 멱등 소비(중복 디스패치·팬아웃 스킵)용.
     * event_id 단독이 아니라 (event_id, recipient)로 확인해야 1 이벤트 → N 수신자 팬아웃이 서로를 안 막는다.
     */
    boolean existsByEventIdAndRecipientTypeAndRecipientId(
            Long eventId, RecipientType recipientType, Long recipientId);

    /** 수신자 인박스(최신순, 페이지). */
    Page<NotificationLog> findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(
            RecipientType recipientType, Long recipientId, Pageable pageable);

    /** 수신자 인박스 — 안읽음만(최신순, 페이지). */
    Page<NotificationLog> findByRecipientTypeAndRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
            RecipientType recipientType, Long recipientId, Pageable pageable);

    /** 안읽음 개수 — 벨 뱃지. */
    long countByRecipientTypeAndRecipientIdAndReadAtIsNull(
            RecipientType recipientType, Long recipientId);

    /** 소유권 스코프 단건 조회(읽음 처리 시 남의 알림을 못 건드리게) — recipient가 일치할 때만 반환. */
    Optional<NotificationLog> findByIdAndRecipientTypeAndRecipientId(
            Long id, RecipientType recipientType, Long recipientId);

    /** 전체 읽음 처리 — 수신자의 안읽음 알림에 읽은 시각 일괄 기록. 처리 건수 반환. */
    @Modifying(clearAutomatically = true)
    @Query("update NotificationLog n set n.readAt = :now "
            + "where n.recipientType = :type and n.recipientId = :recipientId and n.readAt is null")
    int markAllRead(@Param("type") RecipientType recipientType,
            @Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);
}
