-- #8 후속: 취소·반품 사유 집계의 기간 필터 기준이 되는 "취소 시각".
--  · order_item.cancelled_at: 항목이 취소된 시각(취소된 항목만·그 외 NULL).
--
-- 왜 새 컬럼인가 — 기존에 쓸 수 있는 시각이 없었다.
--   updated_at은 교환 옵션 스왑·반품 flip 등 취소와 무관한 변경에도 갱신되고,
--   order_status_history는 주문 단위라 '항목 부분취소'는 아예 전이가 없어 잡히지 않는다.
-- 반품 쪽은 return_request.created_at(요청 시각)이 이미 명확해 추가 컬럼이 필요 없다
--   → 집계 두 축이 모두 "이탈이 발생한 시각"으로 통일된다.
ALTER TABLE order_item ADD COLUMN cancelled_at datetime NULL;

-- 기존 취소 항목 1회 백필(근사) — 취소 후 그 항목이 다시 바뀌는 경로는 사실상 없어 updated_at이 취소 시각과 같다.
-- 정확한 값이 아니라 '대략 언제였나'를 복원하는 용도이며, 이후 취소는 애플리케이션이 정확한 시각을 넣는다.
UPDATE order_item SET cancelled_at = updated_at WHERE status = 'CANCELLED';
