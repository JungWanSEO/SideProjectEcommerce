-- 주문 멱등키 — 체크아웃 중복 제출(더블클릭·모바일 재시도·네트워크 타임아웃 후 재요청) 방지.
--
-- 결제(payment.idempotency_key)에서 이미 쓰던 패턴을 한 계층 위(주문)로 올린 것.
-- 결제는 NOT NULL이지만 주문은 NULL 허용:
--   · 기존 주문 행들이 이미 존재한다(백필할 의미 있는 값이 없다).
--   · 멱등키 없이 만드는 내부/배치 경로(관리자 주문 생성 등)를 막지 않는다.
-- MySQL UNIQUE 인덱스는 NULL을 중복 허용하므로("NULL != NULL") 기존 행 다수가 NULL이어도 충돌하지 않는다.
ALTER TABLE `orders`
    ADD COLUMN `idempotency_key` VARCHAR(80) NULL COMMENT '체크아웃 멱등키(중복 주문 방지)',
    ADD CONSTRAINT `uk_orders_idempotency_key` UNIQUE (`idempotency_key`);
