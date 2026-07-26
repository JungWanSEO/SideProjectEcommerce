-- #4 배송비: 정산 항목이 '배송비(플랫폼 수익)' 엔트리인지 표시하는 플래그.
--  · 대사(reconciliation)는 Σ grossAmount를 pgTransactionId로 묶어 PG 금액과 대조하므로, 같은 거래에
--    배송비 엔트리(sellerId=null·gross=배송비)를 하나 넣으면 Σgross = payment.amount 로 복원돼 MATCHED 유지.
--  · reverseRefunds는 이 플래그로 배송비 엔트리를 셀러별 집계에서 분리해, 전체취소(활성 항목 0)일 때만
--    배송비를 역분개한다(부분취소·반품은 배송비 유지). 셀러 net에는 배송비가 절대 섞이지 않음(플랫폼 수익).
--  · Hibernate(MySQL)는 boolean을 bit로 매핑 → bit 컬럼(ddl-auto validate 일치). 기존 정산분은 b'0'.
ALTER TABLE settlement_entry ADD COLUMN shipping bit(1) NOT NULL DEFAULT b'0';
