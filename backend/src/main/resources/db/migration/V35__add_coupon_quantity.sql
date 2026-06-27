-- V35: 선착순 한정 수량 쿠폰 — 회원이 직접 받는(claim) ISSUED 쿠폰의 수량 한도.
--  · total_quantity = 발급 한도(null = 무제한). issued_count = 지금까지 발급된 수.
--  · 동시 발급 시 초과 발급을 막는 가드는 애플리케이션의 원자적 조건부 UPDATE
--    (issued_count < total_quantity 일 때만 +1) + member_coupon UNIQUE(회원·쿠폰당 1장)로 보장한다.

ALTER TABLE `coupon`
  ADD COLUMN `total_quantity` int          DEFAULT NULL,
  ADD COLUMN `issued_count`   int NOT NULL  DEFAULT 0;
