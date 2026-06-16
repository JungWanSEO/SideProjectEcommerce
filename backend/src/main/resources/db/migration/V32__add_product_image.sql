-- V32: 상품 이미지 갤러리(product_image) — 상품당 여러 장.
--  · 기존 product.image_url(대표 1장)은 그대로 유지하고, 갤러리(추가 이미지들)를 별도 테이블로 둔다.
--  · Product 애그리거트 내부(ProductOption과 동형) — @ManyToOne product, sort_order로 정렬.
--  · ProductOption처럼 product_id FK + (product_id, sort_order) 인덱스.

CREATE TABLE `product_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `url` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` int NOT NULL,
  `product_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_product_image_product` (`product_id`, `sort_order`),
  CONSTRAINT `fk_product_image_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
