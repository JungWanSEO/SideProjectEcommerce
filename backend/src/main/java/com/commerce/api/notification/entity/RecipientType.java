package com.commerce.api.notification.entity;

/**
 * 알림 수신자 유형(#6) — 하나의 알림이 누구의 인박스에 들어가는지.
 *
 * <p>마켓플레이스라 수신자가 구매자만이 아니다: 셀러도 "새 주문 인입·반품 요청·정산 클로백" 같은
 * <b>행동/돈</b> 알림을 받아야 하고, 운영자는 "대사 불일치" 같은 무결성 알림을 받는다.
 * {@code recipientId}의 의미는 유형에 따라 다르다(BUYER/ADMIN=memberId, SELLER=sellerId).
 *
 * <p>저장은 {@code @Enumerated(STRING)} → varchar(20). (네이티브 MySQL ENUM이 아니므로 값 순서 제약 없음.)
 */
public enum RecipientType {
    BUYER,    // 구매자 — recipientId = member_id
    SELLER,   // 셀러   — recipientId = seller_id
    ADMIN     // 운영자 — recipientId = member_id(ADMIN 역할)
}
