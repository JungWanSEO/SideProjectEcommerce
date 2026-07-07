-- V37: 어드민 감사 로그(audit_log) — 운영자 변경(mutation)을 AOP로 자동 기록.
--  · @Auditable이 붙은 어드민 엔드포인트를 AuditAspect가 감싸 행위자·액션·대상·결과를 남긴다.
--  · 성공(SUCCESS)뿐 아니라 실패(FAILURE)도 기록 — "누가 무엇을 시도했다 실패"까지 추적.
--  · actor_member_id: 로그인 회원 ID(인증 못 얻으면 NULL). 회원은 다른 애그리거트라 FK 없이 ID 참조.
--  · action/target_type/target_id: 예) PRODUCT_UPDATE / PRODUCT / 42.
--  · result enum(알파벳순 유지) — 기존 enum 컬럼 컨벤션.
--  · 인덱스: 최근순 조회(created_at)·액션별 필터(action, created_at).

CREATE TABLE `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `actor_member_id` bigint DEFAULT NULL,
  `action` varchar(64) NOT NULL,
  `target_type` varchar(64) DEFAULT NULL,
  `target_id` varchar(64) DEFAULT NULL,
  `detail` varchar(500) DEFAULT NULL,
  `result` enum('FAILURE','SUCCESS') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_created` (`created_at`),
  KEY `idx_audit_action` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
