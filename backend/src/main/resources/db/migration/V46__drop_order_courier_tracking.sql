-- V46: orders의 courier·tracking_number 컬럼 제거 — 멀티셀러 상태 단위(#1 c안) expand/contract 마무리.
--
-- 배경: 배송 송장은 이제 주문 단위가 아니라 셀러별 shipment(V45)에 있다. P5에서 코드가 orders.courier/
--   tracking_number를 더 이상 읽지 않도록 컷오버(OrderResponse는 shipments[]로, Order.advanceShipping는
--   각 shipment에 송장 저장)했으므로, 잉여가 된 두 컬럼을 제거한다.
--
-- ⚠️ 파괴적 스키마 변경(컬럼 DROP) — 되돌리기 어렵다. 기존 SHIPPING/DELIVERED 주문의 단일 송장 값은
--   이 시점에 사라진다(셀러별로 의미 있게 쪼갤 수 없으므로 보존하지 않는다). shipment 백필은 상태만 상속하고
--   courier/tracking은 비운다(V45 백필 러너). MySQL 적용은 백필·코드 컷오버 검증 후 스모크에서 실행한다.

ALTER TABLE `orders`
    DROP COLUMN `courier`,
    DROP COLUMN `tracking_number`;
