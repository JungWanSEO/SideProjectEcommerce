-- V40: 돈 흐름 핫패스 보조 인덱스 2종 (조회 성능). V36의 후속 — 그때 못 넣은/이후 제거된 두 컬럼.
--
-- 배경: 애그리거트 간 FK를 두지 않으므로(ID 참조) 이 두 컬럼엔 자동 인덱스가 없다. 둘 다 취소·환불·정산
--   같은 '돈이 움직이는' 경로에서 매 요청 조회돼, 데이터가 쌓이면 풀스캔 비용이 그대로 지연이 된다.
--
-- ① settlement_entry.payment_id — 정산 멱등 체크(existsByPaymentId)·역분개 상계(findByPaymentId)가 쓴다.
--    V5에서 UNIQUE(payment_id)로 커버됐으나 → V21이 (payment_id, seller_id) 복합으로 교체(leftmost라 계속 커버)
--    → V24가 그 복합 UNIQUE마저 제거(역분개가 같은 결제에 음수 행을 더해야 해 UNIQUE와 충돌) → 이후 인덱스 전무.
--    멱등은 앱(단일 스레드 배치 + existsByPaymentId)이 보장하므로 여기선 UNIQUE가 아니라 일반 인덱스로 조회만 가속.
-- ② payment.order_id — 주문 취소·부분취소가 findByOrderIdAndStatus로 결제를 찾을 때 쓴다. 처음부터 인덱스가 없었다.
--
-- 검증: 운영 MySQL에 Flyway 적용 후 EXPLAIN으로 ref 사용 확인. H2 테스트는 Flyway를 타지 않아 영향 없음.

CREATE INDEX `idx_settlement_entry_payment_id` ON `settlement_entry` (`payment_id`);

CREATE INDEX `idx_payment_order_id` ON `payment` (`order_id`);
