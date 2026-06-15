# ADR-0009: 트랜잭셔널 아웃박스 (dual-write 해소)

- **상태**: 채택(Accepted)
- **근거**: [event-outbox-design.md](../event-outbox-design.md) · [아웃박스 시퀀스](../architecture-diagrams.md#4-시퀀스--트랜잭셔널-아웃박스-발행--소비) · Flyway V8~V10

## 배경 (Context)
결제 완료 시 "DB 상태 PAID 저장"과 "이벤트 발행(알림·배송·정산 디커플링)" 두 가지를 해야 하는데, 우리 DB와 메시지 브로커는 서로 다른 시스템이라 한 트랜잭션으로 못 묶는다(**dual-write**). DB 커밋 후 발행 사이 크래시 = 이벤트 유실, 발행 후 롤백 = 유령 이벤트. 돈이 걸린 결제에 치명적. `PaymentService.pay`는 낙관락 재시도 보존을 위해 의도적으로 `@Transactional`이 없어 결제 저장이 자기 트랜잭션으로 따로 커밋되는 갭도 있었다.

## 결정 (Decision)
**트랜잭셔널 아웃박스**를 도입한다. 결제 완료(`payment.markPaid`+save)와 `outbox_event` INSERT(PENDING)를 **같은 로컬 DB 트랜잭션**(`PaymentCompletionRecorder.saveWithEvent` `@Transactional`)으로 원자 기록한다. `@Scheduled` 폴러(`OutboxRelay`)가 PENDING을 생성순으로 읽어 `EventPublisher` 포트로 발행→PUBLISHED, 실패면 retry_count++ 후 PENDING 유지(at-least-once). 소비자는 멱등(`NotificationLog`의 `event_id` UNIQUE). P2a에서 **지수 백오프**(`next_attempt_at`)+데드레터(FAILED)와 다중 폴러용 **`FOR UPDATE SKIP LOCKED`** 행 클레임 추가. 발행/폴러는 self-invocation 회피 위해 별도 트랜잭션 빈.

## 대안 (Alternatives)
- **(A) @TransactionalEventListener(AFTER_COMMIT)** — durability 없어 커밋~리스너 사이 크래시 시 유실 → 탈락.
- **(B) 2PC/XA 분산 트랜잭션** — 무겁고 느리고 MQ 지원 약함 → 탈락.
- **(C) 결제 코드에서 직접 MQ 호출** — dual-write 그대로 → 탈락.
- **(D) CDC(Debezium)** — 강력하나 인프라 무거워 확장 경로로만.

## 결과 (Consequences)
- **긍정**: 상태 변경↔이벤트 기록이 한 커밋이라 유실/유령 이벤트가 구조적으로 불가, 크래시·MQ 다운에도 복구 후 재발행. `EventPublisher` 포트라 추후 RabbitMQ/Kafka 어댑터 교체에 결제 코드 변경 0.
- **부정**: at-least-once라 소비자 멱등 필수(중복은 정상 시나리오), 폴러 주기 지연·PUBLISHED 행 보관 관리 필요, 현재는 in-process 디스패치 시뮬레이션.
