-- V21: 셀러별 정산 — settlement_entry를 (결제 × 셀러) 단위로 확장. Phase 2 "셀러별 정산" Step 2.
--  · seller_id: 정산 귀속 셀러(nullable = 미귀속/플랫폼 직매입). 다른 애그리거트 → ID 참조(FK 없음).
--  · platform_fee: 플랫폼 판매수수료(원) = gross × platform_fee_rate. PG 수수료(fee)와 별개 차원의 비용.
--  · platform_fee_rate: 적용한 플랫폼 수수료율 스냅샷(= Seller.commissionRate 그때 값). 비율이라 double.
--  · net_amount 의미 변경: 셀러 실수령 = gross - fee - platform_fee.
--    기존 행은 platform_fee=0(DEFAULT)이라 net = gross - fee 그대로 보존(이력 단절 없음).
--  · UNIQUE를 (payment_id) → (payment_id, seller_id) 복합으로 변경: 한 결제가 셀러별로 여러 행을 가지므로.
--    배치 재실행 멱등(결제 단위 skip)은 앱(SettlementService.existsByPaymentId)이 보장하고, 이 제약은 안전망.

ALTER TABLE `settlement_entry`
  ADD COLUMN `seller_id` bigint DEFAULT NULL AFTER `provider`,
  ADD COLUMN `platform_fee` bigint NOT NULL DEFAULT 0 AFTER `fee_rate`,
  ADD COLUMN `platform_fee_rate` double NOT NULL DEFAULT 0 AFTER `platform_fee`,
  DROP INDEX `UK_settlement_payment_id`,
  ADD UNIQUE KEY `UK_settlement_payment_seller` (`payment_id`, `seller_id`);
