package com.commerce.api.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.notification.dto.NotificationResponse;
import com.commerce.api.notification.entity.NotificationCategory;
import com.commerce.api.notification.entity.NotificationLog;
import com.commerce.api.notification.entity.RecipientType;
import com.commerce.api.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 인박스 서비스 통합 테스트(#6 P1) — 본인 스코핑·읽음 처리·안읽음 카운트 + 복합 멱등키 거동.
 */
@SpringBootTest
@Transactional
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationRepository notificationRepository;

    private static final long ALICE = 5001L;
    private static final long BOB = 5002L;

    private NotificationLog buyerNote(long eventId, long memberId, String msg) {
        return NotificationLog.of(eventId, RecipientType.BUYER, memberId, "PAYMENT_COMPLETED",
                NotificationCategory.TRANSACTIONAL, msg, "/orders/1");
    }

    @Test
    @DisplayName("목록 - 본인(BUYER=memberId) 알림만 보인다(남의 것 제외)")
    void list_scopedToOwner() {
        notificationRepository.save(buyerNote(1L, ALICE, "앨리스 결제완료"));
        notificationRepository.save(buyerNote(2L, BOB, "밥 결제완료"));

        PageResponse<NotificationResponse> alice =
                notificationService.getMyNotifications(ALICE, false, PageRequest.of(0, 20));

        assertThat(alice.content()).hasSize(1);
        assertThat(alice.content().get(0).message()).isEqualTo("앨리스 결제완료");
    }

    @Test
    @DisplayName("안읽음 필터 + 카운트 - 읽은 건 제외, 카운트도 안읽음만")
    void unreadOnly_andCount() {
        Long readId = notificationRepository.save(buyerNote(1L, ALICE, "읽을 것")).getId();
        notificationRepository.save(buyerNote(2L, ALICE, "안읽은 것"));
        notificationService.markRead(ALICE, readId);

        PageResponse<NotificationResponse> unread =
                notificationService.getMyNotifications(ALICE, true, PageRequest.of(0, 20));

        assertThat(unread.content()).hasSize(1);
        assertThat(unread.content().get(0).message()).isEqualTo("안읽은 것");
        assertThat(notificationService.unreadCount(ALICE)).isEqualTo(1L);
    }

    @Test
    @DisplayName("읽음 처리 - 본인 것만, 남의 알림은 404(IDOR 차단)")
    void markRead_ownOnly() {
        Long aliceNote = notificationRepository.save(buyerNote(1L, ALICE, "앨리스")).getId();

        // 밥이 앨리스 알림을 읽으려 하면 404
        assertThatThrownBy(() -> notificationService.markRead(BOB, aliceNote))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("알림을 찾을 수 없습니다");

        // 본인은 읽힌다
        notificationService.markRead(ALICE, aliceNote);
        assertThat(notificationRepository.findById(aliceNote).orElseThrow().isRead()).isTrue();
    }

    @Test
    @DisplayName("전체 읽음 - 안읽음 일괄 처리, 처리 건수 반환")
    void markAllRead() {
        notificationRepository.save(buyerNote(1L, ALICE, "a"));
        notificationRepository.save(buyerNote(2L, ALICE, "b"));

        int marked = notificationService.markAllRead(ALICE);

        assertThat(marked).isEqualTo(2);
        assertThat(notificationService.unreadCount(ALICE)).isZero();
    }

    @Test
    @DisplayName("복합 멱등키 - 같은 이벤트라도 수신자가 다르면 공존(팬아웃), 같은 수신자 중복은 거부")
    void compositeIdempotency() {
        // 같은 event_id(1) 이지만 수신자가 다르면 둘 다 저장된다 → 멀티셀러 팬아웃 가능(핵심)
        notificationRepository.saveAndFlush(buyerNote(1L, ALICE, "구매자 알림"));
        notificationRepository.saveAndFlush(NotificationLog.of(1L, RecipientType.SELLER, 700L,
                "PAYMENT_COMPLETED", NotificationCategory.TRANSACTIONAL, "셀러 알림", null));

        // 같은 (event_id, recipient_type, recipient_id) 재삽입은 복합 UNIQUE 위반
        assertThatThrownBy(() ->
                notificationRepository.saveAndFlush(buyerNote(1L, ALICE, "중복")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
