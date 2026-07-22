-- V39: 주문 상태 이력 + 송장(택배사·운송장).
--
-- 왜:
--  · orders.status는 제자리 덮어쓰기라 "언제 출고됐고 왜 취소됐나"를 복구할 수 없었다 → 전이마다 이력 행을 남긴다.
--  · 구매자는 "배송중" 뱃지만 보고 운송장이 없었다 → orders에 courier·tracking_number 추가(평문 표시, 외부 추적 API는 범위 밖).
--
-- enum 값 순서는 orders.status와 동일하게 알파벳순 유지 → Hibernate가 매핑하는 enum DDL과 정합(validate 통과, V4·V34 컨벤션).

ALTER TABLE `orders`
    ADD COLUMN `courier` varchar(40) NULL COMMENT '택배사 (SHIPPING 전이 시 입력)',
    ADD COLUMN `tracking_number` varchar(60) NULL COMMENT '운송장 번호';

CREATE TABLE `order_status_history` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` datetime(6) DEFAULT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    `order_id` bigint NOT NULL,
    -- 이전 상태(생성 시엔 NULL) → 다음 상태. 값 집합은 orders.status와 동일.
    `from_status` enum('CANCELLED','DELIVERED','PAID','PENDING','SHIPPING') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `to_status`   enum('CANCELLED','DELIVERED','PAID','PENDING','SHIPPING') COLLATE utf8mb4_unicode_ci NOT NULL,
    `changed_by` bigint DEFAULT NULL COMMENT '변경 주체 회원 ID (시스템/스케줄러면 NULL)',
    `memo` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    PRIMARY KEY (`id`),
    -- 주문별 이력 시간순 조회 — order_id 선두, id로 안정 정렬.
    KEY `idx_osh_order` (`order_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
