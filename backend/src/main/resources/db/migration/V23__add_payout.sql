-- V23: 셀러 지급 묶음(Payout) — Phase 2 후속(payout 지급 단위).
--  · payout: 셀러에게 정산주기(기간)별로 한 번에 지급하는 단위. SettlementEntry(결제×셀러)를 묶는 헤더.
--  · status enum 알파벳순(PAID, PENDING) — PayoutStatus + Hibernate ENUM DDL 일치(validate).
--  · settlement_entry.payout_id: 묶인 지급 ID(ID 참조, nullable). 다른 행과 FK는 두지 않는다(애그리거트 간 ID 참조).
--    묶음에 들어가면 설정되고, per-entry 지급은 payout_id가 있으면 거부(중복 지급 방지 — 앱에서 가드).

CREATE TABLE `payout` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `seller_id` bigint NOT NULL,
  `period_from` date NOT NULL,
  `period_to` date NOT NULL,
  `total_gross` bigint NOT NULL,
  `total_fee` bigint NOT NULL,
  `total_platform_fee` bigint NOT NULL,
  `total_net` bigint NOT NULL,
  `entry_count` int NOT NULL,
  `status` enum('PAID','PENDING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_payout_seller` (`seller_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `settlement_entry`
  ADD COLUMN `payout_id` bigint DEFAULT NULL AFTER `status`;
