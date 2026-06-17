-- V34: 주문 배송 상태 추가 — PAID → SHIPPING → DELIVERED (forward-only).
--  · orders.status enum에 SHIPPING·DELIVERED를 더한다. 기존 행/값은 그대로(데이터 변환 없음).
--  · 값 순서는 알파벳순으로 유지 — V4와 동일 컨벤션(Hibernate가 매핑하는 enum DDL과 정합 → validate 통과).
--  · 전이 가드(건너뛰기·되돌리기 금지)는 애플리케이션(Order.advanceShipping)이 담당한다.

ALTER TABLE `orders`
  MODIFY COLUMN `status` enum('CANCELLED','DELIVERED','PAID','PENDING','SHIPPING')
      COLLATE utf8mb4_unicode_ci NOT NULL;
