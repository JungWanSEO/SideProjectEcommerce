# ADR-0010: 다중 PG 라우팅 3전략 (클라이언트 선택 / 페일오버 / 비용기반)

- **상태**: 채택(Accepted)
- **근거**: [컴포넌트 다이어그램](../architecture-diagrams.md#5-컴포넌트--다중-pg-포트-어댑터) · [dev-log](../dev-log.md) · Flyway V11~V13

## 배경 (Context)
`PaymentGateway` 포트가 진짜 교체 가능한지 증명하고, 결제 성공률·비용을 운영 관점에서 다루고 싶었다. 단일 PG는 장애 시 결제 전부 실패하고, PG마다 수수료가 달라 비용 최적화 여지가 있다.

## 결정 (Decision)
어댑터를 토스/카카오 2개로 늘리고(공통 `AbstractMockPaymentGateway`로 DRY) `PaymentGatewayRouter`를 둔다. **세 라우팅 전략**:
1. **클라이언트 선택** = 요청 provider로 라우팅, null이면 기본, 미지원이면 400(행 안 남김).
2. **페일오버** = `approveWithFailover()`가 요청 PG 실패 시 나머지 PG를 비용 오름차순(싼 PG부터)으로 순차 시도, 실제 승인 PG를 `PaymentRoutingResult`로 반환해 Payment에 기록(환불도 그 PG로).
3. **비용기반** = provider='AUTO'면 가장 싼 PG 자동 선택.

요율 출처는 **`PaymentGateway.feeRate()` 단일화**(SettlementPolicy 요율 Map 제거) — 라우팅 비용과 정산 수수료 정의가 한 곳, 정산이 라우터에서 요율을 읽음(settlement→payment 정방향). `payment.unavailable-providers` 설정으로 점검 PG 지정.

## 대안 (Alternatives)
- **(A) 단일 PG 고정** — 장애 시 전량 실패·비용 최적화 불가 → 탈락.
- **(B) 요율을 정산·라우터 양쪽에 중복 정의** — 진실의 출처 2개로 불일치 위험, `feeRate()` 단일화로 해소.
- **(C) 거래ID 프리픽스로 provider 파싱** — 프리픽스(`KAKAO-`)≠provider(`KAKAOPAY`)라 불가, 어댑터가 자기 `provider()` 기록.

## 결과 (Consequences)
- **긍정**: 포트-어댑터가 실제 교체·증식 가능함을 증명, 페일오버로 결제 성공률 방어, AUTO로 수수료 최소화, 요율 단일 출처로 정산 정합. 대사/정산이 PG별로 분해·필터(byProvider).
- **부정**: provider가 String이라 타입 안전성은 약함, 라우팅 전략·점검 설정 운영 복잡도 추가.
