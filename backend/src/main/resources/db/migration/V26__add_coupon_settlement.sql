-- V26: 쿠폰 할인 정산 분담 (Step 2). 할인을 셀러별 정산에 반영한다.
--  · orders: 부담주체/셀러귀속 스냅샷 추가(coupon_funded_by "PLATFORM"/"SELLER", coupon_seller_id).
--    enum 대신 varchar 스냅샷 — order→coupon 결합 회피(productName 같은 원시 스냅샷 패턴). 기존 행은 NULL(할인 없음).
--  · settlement_entry: discount_amount(이 항목에 안분된 할인액)·discount_funded_by(부담주체) 추가.
--    net = gross − fee − platform_fee + (PLATFORM 부담이면 discount_amount 환원). 기존 행은 0/NULL → net 불변(이력 단절 없음).
--  · grossAmount는 '할인 후 셀러 몫'으로 저장 → Σgross = payable = PG 정산액 → 대사(group-by-sum) 그대로 MATCHED.

ALTER TABLE `orders`
  ADD COLUMN `coupon_funded_by` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  ADD COLUMN `coupon_seller_id` bigint DEFAULT NULL;

ALTER TABLE `settlement_entry`
  ADD COLUMN `discount_amount` bigint NOT NULL DEFAULT 0,
  ADD COLUMN `discount_funded_by` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL;
