package com.commerce.api.notification.entity;

/**
 * 알림 성격(#6) — 거래성이냐 마케팅성이냐. 발송 정책·법적 구분의 근거.
 *
 * <p><b>TRANSACTIONAL</b>(거래성): 주문·배송·정산 등 거래 이행에 필수 → 수신 동의 없이도 발송 가능.
 * <b>MARKETING</b>(마케팅성): 재입고·할인·리마인드 → 사전 수신 동의(opt-in) 필요·야간 발송 제한 등.
 * 지금은 인앱 인박스만이라 실제 발송 제약은 없지만, 외부 채널(이메일·알림톡) 확장 시 이 플래그로
 * 수신설정(preference)을 분기한다. (자세한 설계=docs/notification-matrix.md)
 */
public enum NotificationCategory {
    TRANSACTIONAL,
    MARKETING
}
