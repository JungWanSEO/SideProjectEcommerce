-- #8 후속 P3: 정산 원장 항목의 '종류' 축을 boolean → enum으로 승격 + 셀러 귀책 과금 금액 컬럼.
--
--  · settlement_entry.entry_kind    : FAULT_CHARGE / RETURN_SHIPPING / SALE / SHIPPING (알파벳순)
--  · settlement_entry.charge_amount : 셀러 귀책 과금(원). net에서 차감된다. 그 외 종류는 0.
--
-- 왜 boolean을 하나 더 늘리지 않는가 — 이 축은 reverseRefunds의 버킷 분기에 직접 쓰인다.
--   역분개는 "지금의 target − 이미 정산된 합"만 남기는 방식이라, 어느 버킷에도 속하지 않은 엔트리가 생기면
--   매 실행마다 그 금액이 통째로 역분개돼 조용히 사라진다(#4가 배송비에서 실제로 밟았던 함정).
--   boolean 3개면 유효 조합 4가지를 8가지로 표현할 수 있어 '빠진 버킷'이 타입으로 막히지 않는다.
--   enum이면 "모든 엔트리는 정확히 하나의 kind, 모든 kind는 자기 target"이 구조로 강제된다.
--
-- 왜 gross가 아니라 별도 charge 컬럼인가(FAULT_CHARGE) — 셀러↔플랫폼 내부 정산 조정액은
--   PG 원장에 대응 금액이 없다. gross에 실으면 대사(Σgross = PG 승인액)가 즉시 AMOUNT_MISMATCH로 튄다.
--   반대로 고객에게서 덜 환불해 실제로 보유한 회수비(RETURN_SHIPPING)는 PG 잔여에 실재하는 돈이라 gross에 싣는다.
--
-- expand/contract: shipping 컬럼은 여기서 남겨 둔다(이중 출처 일시 허용).
--   코드 컷오버 → MySQL 스모크로 정합 확인 → 다음 마이그레이션에서 DROP (V46의 파괴적 변경 프로토콜 그대로).
ALTER TABLE settlement_entry ADD COLUMN entry_kind varchar(20) NOT NULL DEFAULT 'SALE';
ALTER TABLE settlement_entry ADD COLUMN charge_amount bigint NOT NULL DEFAULT 0;

-- 기존 배송비 엔트리를 새 축으로 백필. 나머지는 DEFAULT 'SALE' 그대로가 정답이다.
UPDATE settlement_entry SET entry_kind = 'SHIPPING' WHERE shipping = 1;
