-- #8 취소·환불 사유 taxonomy: 구조화된 사유 코드(enum STRING). 기록·집계 전용(돈 경로 무영향).
--  · order_item.cancel_reason: 항목 취소 시 사유(취소된 항목만·없으면 NULL). 시스템 취소(만료 등)는 NULL.
--  · return_request.reason_code: 반품/교환 요청 시 구조화 사유(자유텍스트 reason과 병행·없으면 NULL).
-- 둘 다 add-only·nullable → 기존 행 무영향. 값은 CHANGE_OF_MIND/WRONG_ORDER/DELIVERY_DELAY/OUT_OF_STOCK/
--   DEFECTIVE/WRONG_DELIVERY/OTHER(CancelReason enum).
ALTER TABLE order_item ADD COLUMN cancel_reason varchar(30) NULL;
ALTER TABLE return_request ADD COLUMN reason_code varchar(30) NULL;
