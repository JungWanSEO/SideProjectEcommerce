-- V29: 행동 로그(activity) — 개인화 추천 Step 1.
--  · activity_log: 회원의 상품 조회 이벤트를 append-only로 기록(반복 조회 = 빈도 신호).
--    찜·구매는 각 도메인 테이블(wishlist·order_item)에서 신호로 읽으므로 여기엔 조회(VIEW)만 둔다.
--  · type enum(VIEW) — 단일값(향후 행동 유형 확장 대비, 알파벳순 유지).
--  · 인덱스 (member_id, created_at): 추천 배치의 "이 회원 최근 조회" 윈도우 집계용.
--  · 회원·상품은 다른 애그리거트라 FK 없이 ID 참조(architecture.md §11).

CREATE TABLE `activity_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `type` enum('VIEW') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_activity_member` (`member_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
