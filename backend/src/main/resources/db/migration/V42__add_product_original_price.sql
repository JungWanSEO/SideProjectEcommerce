-- V42: 상품 정가(original_price) 추가 — 할인가 모델(정가 vs 판매가).
--
-- price(판매가·결제 기준)는 그대로 두고, original_price(정가·취소선)만 더한다. null이면 비할인.
-- original_price > price 일 때만 할인이며 할인율은 응답/화면에서 (original_price − price)/original_price로 계산.
-- 결제·정산·환불·대사는 언제나 price 기준이라 이 컬럼은 표시/정렬 전용(돈 흐름 무개입).
--
-- 기존 상품은 전부 비할인(null)으로 백필된다(DEFAULT NULL). nullable이라 validate와도 정합.

ALTER TABLE `product` ADD COLUMN `original_price` bigint DEFAULT NULL AFTER `price`;
