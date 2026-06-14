-- V22: 셀러 로그인 콘솔 — 회원에 SELLER 역할 + 운영 셀러 연결. Phase 2 후속(셀러 콘솔).
--  · role enum에 'SELLER' 추가(알파벳순 'ADMIN','SELLER','USER' — Role enum + Hibernate ENUM DDL 일치).
--    기존 행은 'ADMIN'/'USER' 그대로 유효(값 집합만 넓힘).
--  · seller_id: SELLER 회원이 운영하는 셀러(ID 참조, nullable). 다른 애그리거트라 FK 없음(architecture.md §11).
--    셀러 콘솔이 "내 정산만" 스코핑하는 키. 회원 1명 ↔ 셀러 1곳.

ALTER TABLE `member`
  MODIFY COLUMN `role` enum('ADMIN','SELLER','USER') COLLATE utf8mb4_unicode_ci NOT NULL,
  ADD COLUMN `seller_id` bigint DEFAULT NULL AFTER `role`;
