-- V18: 셀러(입점사) 도메인 추가 — Phase 2 "셀러별 정산" Step 1a.
--  · seller: 플랫폼에 입점한 판매 주체. 한 셀러가 여러 브랜드를 운영(seller 1:N brand) — brand.seller_id로 연결(V19).
--  · commission_rate: 플랫폼 판매수수료율(예: 0.10 = 10%). PG 수수료와 별개 차원의 비용.
--    비율이고 합산하지 않으므로 double(money는 bigint).
--  · status enum 값은 알파벳순(ACTIVE, SUSPENDED) — SellerStatus + Hibernate ENUM DDL과 일치 → validate 통과.
--  · payout_account/business_number: 운영 메타(nullable). 정산 금액 계산엔 직접 관여하지 않는다.
--  · name UNIQUE: 셀러명 중복 방지(앱 SellerService.existsByName + DB 제약 이중 보장).

CREATE TABLE `seller` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `commission_rate` double NOT NULL,
  `status` enum('ACTIVE','SUSPENDED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `payout_account` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `business_number` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_seller_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
