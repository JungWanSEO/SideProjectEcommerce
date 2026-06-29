-- V36: 자주 조회되는 비-PK 컬럼에 보조 인덱스 추가 (조회 성능).
--
-- 배경: 이 프로젝트는 애그리거트 간 FK 제약을 두지 않고 'ID 참조'로 느슨하게 연결한다(분산 친화·결합도↓).
--   그래서 JPA가 자동으로 인덱스를 만들어 주는 곳은 PK와 일부 FK(order_item.order_id)뿐이고,
--   member_id / product_id 같은 필터·조인 컬럼은 풀스캔이 될 수 있다 → 데이터가 쌓이면 목록 조회가 느려진다.
--
-- 선정 기준(중복·무용 인덱스 회피):
--   · 이미 복합 UNIQUE의 leftmost-prefix로 커버되는 건 제외.
--       member_coupon(member_id,coupon_id) / wishlist(member_id,product_id) / recommendation(member_id,product_id)
--       → member_id 단독 조회는 이 인덱스의 앞 컬럼으로 이미 탐색 가능.
--   · 저카디널리티 컬럼은 제외. 예: orders.status(값 6종) — 분포가 치우쳐 옵티마이저가 인덱스를 잘 안 쓰고,
--       대시보드의 status 집계는 전체 집계라 인덱스 이득이 작다 → 일부러 만들지 않는다.
--   · 아래는 모두 고카디널리티 + 실제 반복 쿼리에 직접 묶인다.
--
-- 검증: 운영 MySQL에 Flyway로 적용 후 EXPLAIN으로 ref/range 사용 확인. H2 테스트는 Flyway를 타지 않아 영향 없음.

-- 주문 내역(마이페이지): orders WHERE member_id = ? — review의 복합키와 달리 orders엔 member_id 인덱스가 없었다.
CREATE INDEX `idx_orders_member_id` ON `orders` (`member_id`);

-- 상품 상세의 리뷰 목록: review WHERE product_id = ?.
--   기존 UNIQUE(member_id, product_id)는 product_id가 두 번째 컬럼이라 product_id 단독 조회엔 못 쓴다.
CREATE INDEX `idx_review_product_id` ON `review` (`product_id`);

-- "이 상품 구매한 회원인가"(리뷰 작성 자격) 등에서 order_item을 product_id로 필터한다.
--   order_id는 FK 인덱스가 이미 있고, product_id는 없었다.
CREATE INDEX `idx_order_item_product_id` ON `order_item` (`product_id`);

-- 셀러별 정산 집계/조회: settlement_entry WHERE seller_id = ?.
--   UNIQUE(payment_id, seller_id)는 seller_id가 두 번째라 seller_id 단독 조회엔 못 쓴다.
CREATE INDEX `idx_settlement_entry_seller_id` ON `settlement_entry` (`seller_id`);

-- 지급(payout)에 묶인 정산 항목 조회: settlement_entry WHERE payout_id = ? (지급 상세).
CREATE INDEX `idx_settlement_entry_payout_id` ON `settlement_entry` (`payout_id`);
