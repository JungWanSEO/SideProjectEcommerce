-- #4 배송비: orders.shipping_fee 스냅샷 컬럼(정액+무료임계). discount_amount(V25)와 동형 add-only.
-- payable = 소계 − 할인 + 배송비(활성 항목 있을 때). 배송비는 플랫폼 수익 — 셀러 정산 net에는 미포함.
-- 기존 주문은 DEFAULT 0(배송비 없던 시점) → 과거 payable/환불/정산/대사 불변.
ALTER TABLE orders ADD COLUMN shipping_fee BIGINT NOT NULL DEFAULT 0;
