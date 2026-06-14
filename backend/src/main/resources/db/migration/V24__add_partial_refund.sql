-- V24: 부분환불 안분 — 항목 단위 취소 + 결제 부분환불 + 정산 역분개 지원. Phase 2 후속.
--  · order_item.status: 항목 단위 취소(ACTIVE/CANCELLED). 기존 행은 ACTIVE 백필. enum 알파벳순.
--  · payment.refunded_amount: 누적 환불액(부분환불). 결제액 도달 시 전액 환불(CANCELLED).
--  · settlement_entry: (payment_id, seller_id) UNIQUE 제거 — 환불 상계(역분개)는 같은 (결제,셀러)에
--    음수 정산 항목을 추가해야 하므로 복합 UNIQUE와 충돌한다. 정방향 멱등성은 앱(existsByPaymentId)이
--    여전히 보장하므로 DB 제약 없이도 중복 생성되지 않는다.

ALTER TABLE `order_item`
  ADD COLUMN `status` enum('ACTIVE','CANCELLED') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE `payment`
  ADD COLUMN `refunded_amount` bigint NOT NULL DEFAULT 0;

ALTER TABLE `settlement_entry`
  DROP INDEX `UK_settlement_payment_seller`;
