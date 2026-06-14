-- V20: 주문 항목에 셀러 귀속 스냅샷(brand_id·seller_id) 추가 — Phase 2 "셀러별 정산" Step 1b.
--  · 주문 시점에 상품→브랜드(brand_id)→셀러(seller_id)를 동결한다(이력 보존).
--    orderPrice/size 스냅샷과 같은 철학 — 이후 상품의 브랜드나 브랜드의 셀러 귀속이 바뀌어도
--    과거 주문의 정산 귀속은 불변.
--  · 상품·브랜드·셀러는 다른 애그리거트 → ID 참조(FK 없음, architecture.md §11). nullable:
--    브랜드 미지정 상품·셀러 미귀속 브랜드 허용(미귀속 = 플랫폼 직매입 버킷).
--  · 기존 주문 항목의 brand_id/seller_id는 NULL로 남는다(신규 주문부터 채워짐 — 이력 단절 허용).
--  · option_id 옆(ID 참조 묶음)에 둔다.

ALTER TABLE `order_item`
  ADD COLUMN `brand_id` bigint DEFAULT NULL AFTER `option_id`,
  ADD COLUMN `seller_id` bigint DEFAULT NULL AFTER `brand_id`;
