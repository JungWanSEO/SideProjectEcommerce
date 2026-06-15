-- V27: 회원 쿠폰함 (Step 3) — 공개형/발급형 구분 + 발급 단일 사용.
--  · coupon.issue_type: PUBLIC(코드 입력·무제한, 기존 동작) / ISSUED(회원 발급·지갑·단일 사용). 기존 행은 PUBLIC 백필.
--    enum 값 순서 = Java CouponIssueType(ISSUED, PUBLIC) = Hibernate ENUM DDL → ddl-auto: validate 통과(알파벳순).
--  · member_coupon: 회원에게 발급된 쿠폰 1장. (member_id, coupon_id) UNIQUE(회원·쿠폰당 1장 — 중복 발급 방지).
--    status enum(UNUSED, USED) 알파벳순. used_at nullable(발급 일시는 created_at). 다른 애그리거트라 FK 없이 ID 참조.

ALTER TABLE `coupon`
  ADD COLUMN `issue_type` enum('ISSUED','PUBLIC') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PUBLIC';

CREATE TABLE `member_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `status` enum('UNUSED','USED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_member_coupon` (`member_id`, `coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
