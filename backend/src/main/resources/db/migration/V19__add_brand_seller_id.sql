-- V19: 브랜드에 셀러 귀속(seller_id) 추가 — Phase 2 "셀러별 정산" Step 1a.
--  · 한 셀러가 여러 브랜드를 소유(seller 1:N brand). brand가 seller_id(ID 참조)를 보유한다.
--  · FK는 두지 않는다(애그리거트 간 ID 참조 원칙 — architecture.md §11). nullable: 미귀속(플랫폼 직매입) 허용.
--  · 기존 브랜드 행의 seller_id는 NULL로 남는다 → 런타임에 셀러 생성 후 매핑(seller 1:N brand 시연).
--  · name 옆(AFTER name)에 둔다.

ALTER TABLE `brand`
  ADD COLUMN `seller_id` bigint DEFAULT NULL AFTER `name`;
