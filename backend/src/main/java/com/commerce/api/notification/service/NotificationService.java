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
 * 알림 인박스 서비스(#6). 모든 조회·읽음은 <b>수신자 본인 것으로만</b> 스코핑된다(recipient 일치를 쿼리에서 강제
 * → IDOR 차단). 구매자(BUYER=memberId)·셀러(SELLER=sellerId)가 각자의 진입점으로 자기 인박스만 본다.
 *
 * <p>스코프별 공개 메서드는 얇은 래퍼이고, 실제 로직은 (recipientType, recipientId)로 파라미터화한 private 코어가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    // === 구매자(BUYER) 진입점 — recipientId = memberId ===

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getMyNotifications(
            Long memberId, boolean unreadOnly, Pageable pageable) {
        return list(RecipientType.BUYER, memberId, unreadOnly, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long memberId) {
        return count(RecipientType.BUYER, memberId);
    }

    @Transactional
    public void markRead(Long memberId, Long notificationId) {
        markRead(RecipientType.BUYER, memberId, notificationId);
    }

    @Transactional
    public int markAllRead(Long memberId) {
        return markAll(RecipientType.BUYER, memberId);
    }

    // === 셀러(SELLER) 진입점 — recipientId = sellerId (SellerConsoleService가 sellerId 도출) ===

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getSellerNotifications(
            Long sellerId, boolean unreadOnly, Pageable pageable) {
        return list(RecipientType.SELLER, sellerId, unreadOnly, pageable);
    }

    @Transactional(readOnly = true)
    public long sellerUnreadCount(Long sellerId) {
        return count(RecipientType.SELLER, sellerId);
    }

    @Transactional
    public void sellerMarkRead(Long sellerId, Long notificationId) {
        markRead(RecipientType.SELLER, sellerId, notificationId);
    }

    @Transactional
    public int sellerMarkAllRead(Long sellerId) {
        return markAll(RecipientType.SELLER, sellerId);
    }

    // === (recipientType, recipientId)로 파라미터화한 코어 ===

    private PageResponse<NotificationResponse> list(
            RecipientType type, Long recipientId, boolean unreadOnly, Pageable pageable) {
        Page<NotificationLog> page = unreadOnly
                ? notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
                        type, recipientId, pageable)
                : notificationRepository.findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(
                        type, recipientId, pageable);
        return PageResponse.from(page.map(NotificationResponse::from));
    }

    private long count(RecipientType type, Long recipientId) {
        return notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(type, recipientId);
    }

    private void markRead(RecipientType type, Long recipientId, Long notificationId) {
        NotificationLog n = notificationRepository
                .findByIdAndRecipientTypeAndRecipientId(notificationId, type, recipientId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));
        n.markRead();   // 더티체킹으로 read_at 반영
    }

    private int markAll(RecipientType type, Long recipientId) {
        return notificationRepository.markAllRead(type, recipientId, LocalDateTime.now());
    }
}
