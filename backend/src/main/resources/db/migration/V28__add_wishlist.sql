-- V28: 위시리스트(찜) 도메인 — Phase 2 후속(추천 디딤돌).
--  · wishlist 테이블: 1인 1상품 1찜(member_id+product_id UNIQUE). 상태 컬럼 없음(행 존재 자체가 찜).
--  · product에 찜 비정규화 카운터(wishlist_count) 추가 — 찜 추가/해제 시 원자 UPDATE로 증감(인기도 신호).
--    기존 상품 행은 DEFAULT 0. (리뷰 평점 카운터 rating_count/rating_sum과 같은 패턴 — V15 참고.)
--  · 회원·상품은 다른 애그리거트라 FK 제약은 두지 않는다(ID 참조 원칙 — architecture.md §11).

ALTER TABLE `product`
  ADD COLUMN `wishlist_count` int NOT NULL DEFAULT 0 AFTER `rating_sum`;

CREATE TABLE `wishlist` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wishlist_member_product` (`member_id`, `product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
