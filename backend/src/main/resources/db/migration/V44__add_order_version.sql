-- V44: orders에 낙관적 락 버전 추가 — 주문 생명주기 전이 동시성(#2 리뷰 후속).
--
-- TTL 만료 배치(OrderExpiryService)와 결제(pay)/취소가 같은 PENDING 주문을 락 없이 경합하면
-- "결제됐는데 만료로 취소됨" 같은 상태 뒤집힘이 생긴다. @Version으로 늦게 커밋하는 전이를 충돌 실패시켜
-- 직렬화한다(결제는 @Retryable이 재시도 → 이미 취소면 409로 깔끔히 실패). 기존 행은 0으로 백필.

ALTER TABLE `orders` ADD COLUMN `version` bigint NOT NULL DEFAULT 0;
