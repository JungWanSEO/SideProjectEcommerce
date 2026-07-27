package com.commerce.api.notification.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.notification.dto.NotificationResponse;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 인박스 서비스(#6 P1) — 구매자(BUYER) 스코프. 모든 조회·읽음은 로그인 사용자 본인 것으로만 스코핑된다
 * (남의 알림을 못 읽게 recipient 일치를 쿼리에서 강제 → IDOR 차단).
 *
 * <p>셀러 인박스(P3)는 수신자 유형이 SELLER인 별도 진입점에서 다룬다 — 여기선 BUYER로 고정.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 내 알림 목록(최신순, 페이지). unreadOnly=true면 안읽음만. */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(
            Long memberId, boolean unreadOnly, Pageable pageable) {
        Page<NotificationLog> page = unreadOnly
                ? notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
                        RecipientType.BUYER, memberId, pageable)
                : notificationRepository.findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(
                        RecipientType.BUYER, memberId, pageable);
        return PageResponse.from(page.map(NotificationResponse::from));
    }

    /** 안읽음 개수(벨 뱃지). */
    @Transactional(readOnly = true)
    public long unreadCount(Long memberId) {
        return notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                RecipientType.BUYER, memberId);
    }

    /** 단건 읽음 처리 — 본인(BUYER=memberId) 알림만. 없거나 남의 것이면 404. */
    @Transactional
    public void markRead(Long memberId, Long notificationId) {
        NotificationLog n = notificationRepository
                .findByIdAndRecipientTypeAndRecipientId(notificationId, RecipientType.BUYER, memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));
        n.markRead();   // 더티체킹으로 read_at 반영
    }

    /** 전체 읽음 처리 — 안읽음 일괄 갱신. 처리 건수 반환. */
    @Transactional
    public int markAllRead(Long memberId) {
        return notificationRepository.markAllRead(RecipientType.BUYER, memberId, LocalDateTime.now());
    }
}
