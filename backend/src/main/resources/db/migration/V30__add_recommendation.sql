-- V30: 추천 결과(recommendation) — 개인화 추천 Step 2.
--  · recommendation: "나를 위한 추천"을 배치가 회원별로 미리 계산해 저장(precompute). 읽기는 정렬 조회만.
--  · (member_id, product_id) UNIQUE: 한 회원에게 같은 상품은 한 줄. 배치는 회원별로 지우고 다시 넣는다.
--    → 이 UNIQUE의 leftmost(member_id)가 "회원별 조회"도 받쳐 별도 인덱스 불필요.
--  · score double: 친화도+인기도 가중합(돈 아님 → double, V12서 double↔double validate 통과 확인).
--  · 순위는 score 내림차순으로 읽어 정함(rank 컬럼 없음 — rank는 MySQL 예약어).
--  · 회원·상품은 다른 애그리거트라 FK 없이 ID 참조.

CREATE TABLE `recommendation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `score` double NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recommendation_member_product` (`member_id`, `product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
