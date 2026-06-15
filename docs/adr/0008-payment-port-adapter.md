# ADR-0008: 결제 = 포트-어댑터 (모의 PG)

- **상태**: 채택(Accepted)
- **근거**: [architecture.md §13](../architecture.md) · [payment-architecture-study.md](../payment-architecture-study.md) · [컴포넌트 다이어그램](../architecture-diagrams.md#5-컴포넌트--다중-pg-포트-어댑터)

## 배경 (Context)
실제 PG(토스/포트원) 연동 없이 결제 흐름을 학습/시연해야 했지만, 추후 실제 PG로 **무중단 교체**가 가능해야 했다. 결제는 외부 경계라 기술 세부(PG API)가 비즈니스 핵심을 흔들면 안 된다(의존성 역전).

## 결정 (Decision)
결제 외부 연동을 **`PaymentGateway` 인터페이스(아웃바운드 포트)** 로 추상화하고 **모의 어댑터**로 시작한다 — 외부 호출 없이 `pgTransactionId` 발급·승인/실패 결정. `PaymentService`(콘크리트, 인터페이스 없음)는 PG 구현을 모르고 포트에만 의존(DIP). `approve/refund/fetchSettlements`를 포트로 둬 운영 전환 시 같은 인터페이스에 실제 PG 어댑터만 추가한다. 재고 차감은 결제 승인 시점으로 옮기고 상태머신(OrderStatus PENDING→PAID/CANCELLED, PaymentStatus READY→PAID/FAILED→CANCELLED)을 도입했다. 멱등성 = `idempotencyKey`(UUID) unique로 중복 결제 방지.

## 대안 (Alternatives)
- **(A) PG 호출을 서비스에 하드코딩** — 교체·테스트 어려움 → 탈락.
- **(B) 풀 헥사고날(DB까지 포트화)** — 도메인↔JPA 매핑 보일러플레이트 폭증, 이 규모엔 오버엔지니어링이라 **외부 경계(PG) 한 곳만** 포트로.
- **(C) PaymentService에 인터페이스 부여** — 구현 1개면 CGLIB 프록시·Mockito로 충분해 YAGNI 위반이라 콘크리트 유지.

## 결과 (Consequences)
- **긍정**: 운영 전환 시 어댑터 교체만으로 코드 변경 0, 모의 PG로 실패 시나리오 테스트 용이, 다중 PG 어댑터 확장의 기반.
- **부정**: 의도적 단순화로 `Payment` 엔티티가 `HttpStatus`(웹 개념)를 import하는 의존성 규칙 위반을 알고도 허용(커지면 도메인 예외 + `@ExceptionHandler`로 분리 예정). 주문 PAID 커밋↔결제 PAID 저장 사이 원자성은 아웃박스로 보강.
