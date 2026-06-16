-- V33: 카테고리 계층화(2단계) — category.parent_id 추가.
--  · parent_id NULL = 최상위(부모) 카테고리, 값 = 그 부모의 자식.
--  · 다른 행(카테고리)을 ID로 참조(architecture.md §11) — 객체 연관/FK 없이 parentId Long(recommendation·cooccurrence 패턴).
--  · 2단계 제한(부모→자식까지)은 서비스가 검증한다(자식 밑에 또 자식 금지).

ALTER TABLE `category` ADD COLUMN `parent_id` bigint DEFAULT NULL;
