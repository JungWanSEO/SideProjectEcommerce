-- V31: 함께 산 상품(product_cooccurrence) — 개인화 추천 Step 3.
--  · co-occurrence: "이 상품을 산 사람들이 함께 산 상품"을 배치가 미리 계산해 저장(precompute). 읽기는 정렬 조회만.
--  · recommendation(회원별 "나를 위한 추천")과 달리 이건 상품↔상품 관계(회원 무관)라 의미가 달라 별도 테이블로 둔다.
--  · reference_product_id = 상세 페이지의 기준 상품, product_id = 그와 함께 산(추천) 상품.
--  · co_buy_count = 두 상품이 함께 담긴 '서로 다른 PAID 주문' 수(COUNT DISTINCT order) = 신호 강도.
--  · score double = co_buy_count 가중 + 인기도 타이브레이크(돈 아님 → double). 읽기는 score 내림차순 정렬.
--  · (reference_product_id, product_id) UNIQUE: 한 기준 상품에 같은 추천 상품은 한 줄. 배치는 전부 지우고 다시 넣는다.
--    → 이 UNIQUE의 leftmost(reference_product_id)가 "기준 상품별 조회"도 받쳐 별도 인덱스 불필요.
--  · 상품은 다른 애그리거트라 FK 없이 ID 참조(architecture.md §11).

CREATE TABLE `product_cooccurrence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `reference_product_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `co_buy_count` int NOT NULL,
  `score` double NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cooccurrence_ref_product` (`reference_product_id`, `product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
