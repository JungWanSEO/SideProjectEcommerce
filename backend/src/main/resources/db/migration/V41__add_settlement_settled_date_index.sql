-- V41: settlement_entry.settled_date 보조 인덱스 (일자별 대사 윈도우 조회).
--
-- 배경: 대사(ReconciliationService.reconcile)는 실무상 매일 전날치만 대조한다 →
--   settled_date 범위로 좁혀 읽는데(findBySettledDateWindow), 인덱스가 없어 매번 풀스캔이었다.
--   전체 대사(무윈도우)는 어차피 findAll이라 이 인덱스와 무관하고, 윈도우 대사만 이득을 본다.
--
-- 검증: 운영 MySQL에 Flyway 적용 후 EXPLAIN으로 range 사용 확인. H2 테스트는 Flyway를 타지 않아 영향 없음.

CREATE INDEX `idx_settlement_entry_settled_date` ON `settlement_entry` (`settled_date`);
