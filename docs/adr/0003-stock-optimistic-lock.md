# ADR-0003: 재고 동시성 = @Version 낙관적 락 + 재시도(새 트랜잭션)

- **상태**: 채택(Accepted)
- **근거**: [architecture.md §11](../architecture.md) · `OrderConcurrencyTest`

## 배경 (Context)
동시 주문/결제 시 같은 옵션(SKU) 재고를 동시 차감하면 **초과판매(oversell)** 가 발생한다. 초기엔 알려진 한계(TODO)였고, 동시성 테스트로 초과판매 0을 증명해야 했다.

## 결정 (Decision)
재고 보유 엔티티(`ProductOption`)에 **`@Version` 낙관적 락**을 둬 커밋 시점에 동시 차감 충돌을 감지한다. **재시도는 트랜잭션 바깥에서 새 트랜잭션**으로 수행한다 — `OrderService`에 `@Retryable(OptimisticLockingFailureException, maxAttempts=3, backoff 100ms)`을 걸고, 실제 작업은 별도 빈 `OrderProcessor`의 `@Transactional` 메서드(place/checkout/pay)에 위임한다. 빈을 분리해야 프록시 경유로 "충돌→롤백→새 트랜잭션 재시도"가 보장된다. 재고가 정말 부족하면 `BusinessException(409)`으로 재시도 없이 실패한다. 결제 도입 후 재고 차감 지점은 주문 생성 → **결제 승인(pay)** 단계로 이동했고 재시도도 결제 워커로 따라갔다.

## 대안 (Alternatives)
- **(A) 비관적 락(SELECT FOR UPDATE)** — 경합 적은 워크로드에 과도, 락 보유로 처리량↓ → 탈락(스케일아웃 시 Redis 분산락은 확장지점으로만 명시).
- **(B) 같은 트랜잭션 안 재시도** — 롤백된 트랜잭션 재사용 불가라 동작 안 함 → 별도 트랜잭션 빈 분리.
- **(C) DB 유니크/원자 UPDATE만** — 도메인 규칙(재고부족 메시지·상태) 표현이 어려움.

## 결과 (Consequences)
- **긍정**: 초과판매 0을 `OrderConcurrencyTest`로 증명, 경합 적을 때 락 비용 없음.
- **부정**: 충돌 시 재시도 지연(100ms 백오프), 빈 분리·프록시 이해 필요(self-invocation 함정). 스케일아웃 시 분산락 보완 여지는 확장지점으로만 남김.
