-- #6 알림 인박스(P1): notification_log를 "읽을 수 있는" 알림으로 확장한다.
--  기존엔 event_id·type·message만 있어 "보냈다고 치는 모의 로그"였고 수신자·읽음이 없어 아무도 못 읽었다.
--
--  · recipient_type/recipient_id : 이 알림이 누구 인박스인지(BUYER/SELLER/ADMIN + memberId 또는 sellerId).
--  · category                    : 거래성(TRANSACTIONAL)/마케팅성(MARKETING) — 수신설정·법적 구분 근거.
--  · link                        : 딥링크 대상(선택, 예: /orders/42).
--  · read_at                     : 읽은 시각(NULL=안읽음 → 벨 뱃지 카운트 기준).
--
--  멱등 키 재설계(중요): 하나의 이벤트가 여러 수신자에게 알림을 만들 수 있으므로(멀티셀러 주문 1건 = 셀러 N명)
--    event_id 단독 UNIQUE(uk_notification_event_id)는 두 번째 수신자 INSERT를 막아버린다 → 제거하고
--    (event_id, recipient_type, recipient_id) 복합 UNIQUE로 교체(재도착·팬아웃 중복 방어).
--
--  신규 컬럼은 nullable(add-only) → 기존 행(수신자 없는 초기 모의 로그) 무영향. 새 알림은 앱이 전부 채운다.
ALTER TABLE `notification_log` ADD COLUMN `recipient_type` varchar(20)   NULL;
ALTER TABLE `notification_log` ADD COLUMN `recipient_id`   bigint        NULL;
ALTER TABLE `notification_log` ADD COLUMN `category`       varchar(20)   NULL;
ALTER TABLE `notification_log` ADD COLUMN `link`           varchar(255)  NULL;
ALTER TABLE `notification_log` ADD COLUMN `read_at`        datetime      NULL;

ALTER TABLE `notification_log` DROP INDEX `uk_notification_event_id`;
ALTER TABLE `notification_log` ADD CONSTRAINT `uk_notification_event_recipient`
    UNIQUE (`event_id`, `recipient_type`, `recipient_id`);
