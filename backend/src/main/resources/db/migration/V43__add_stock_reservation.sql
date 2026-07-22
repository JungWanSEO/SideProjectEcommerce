-- V43: 재고 예약(TTL) — 오버셀 구간 제거(#2 c안).
--
-- 배경: 지금 PENDING 주문은 재고를 전혀 안 잡아 주문 생성~결제 사이에 레이스 구간이 열려 있다
--   (100명이 마지막 1개로 동시 체크아웃 → 먼저 결제한 1명만 성공, 나머지는 결제 단계 409).
--   → 주문 생성 시점에 재고를 '예약'해 그 순간 오버셀을 원자적으로 차단한다.
--
-- 모델:
--   · product_option.reserved = 예약됐지만 아직 결제 안 된 수량. 가용재고 = stock − reserved.
--   · 예약(주문 생성)  : 원자적 조건부 UPDATE `reserved = reserved + q WHERE stock - reserved >= q`
--       (0행이면 품절 → 409. 선착순 쿠폰 incrementIssuedCount와 동형 — 앱 락 없이 행 락으로 직렬화.)
--   · 소진(결제 확정)  : stock = stock − q, reserved = reserved − q  (예약을 실재고 차감으로 전환).
--   · 해제(만료·취소)  : reserved = reserved − q  (OrderExpiryService가 PENDING 만료 시 함께 호출).
--   · stock_reservation = (주문,옵션)별 예약 한 행(수량·만료·상태) — 주문 생명주기로 해제를 추적한다.
--
-- enum 값은 알파벳순(hbm2ddl validate 통과·저장 정합).

ALTER TABLE `product_option` ADD COLUMN `reserved` int NOT NULL DEFAULT 0 AFTER `stock`;

CREATE TABLE `stock_reservation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `order_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `status` enum('ACTIVE','CONSUMED','RELEASED') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_stock_reservation_order_status` (`order_id`,`status`),
  KEY `idx_stock_reservation_item_status` (`order_item_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
