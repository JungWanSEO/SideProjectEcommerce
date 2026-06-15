# ADR-0006: 운영 하드닝 = 시크릿 OS 환경변수(12-factor) + Flyway(ddl validate)

- **상태**: 채택(Accepted)
- **근거**: [architecture.md](../architecture.md) · [dev-log](../dev-log.md) · `backend/src/main/resources/db/migration`

## 배경 (Context)
초기엔 `ddl-auto:update`로 스키마를 Hibernate가 자동 변경하고 JWT 시크릿이 `application.yml`에 평문이었다 — 운영 부적합. 외부 노출 MySQL이 약한 비번으로 **랜섬웨어 침해**를 당한 보안 사고도 겪어 시크릿·노출 관리의 중요성을 체감했다.

## 결정 (Decision)
시크릿(JWT 시크릿, DB 비번, PG 점검설정 등)을 **OS 환경변수/.env로 분리(12-factor config)** 하고, 코드/yml에서 평문 시크릿을 제거한다. 스키마는 **Flyway 마이그레이션이 단일 통제**하며 `ddl-auto`는 **validate**로 전환한다(엔티티↔스키마 불일치면 부팅 실패). V1부터 누적 마이그레이션(현재 **V28**까지)으로 모든 스키마 변경을 버전 관리한다. MySQL은 `127.0.0.1` 바인딩 + 강한 비번으로 외부 노출 차단.

## 대안 (Alternatives)
- **(A) ddl-auto:update 유지** — 운영에서 의도치 않은 스키마 변경·데이터 손실 위험 → 탈락.
- **(B) 시크릿을 yml/코드에** — 유출 위험 → 탈락.
- **(C) ddl-auto:none** — 검증 안전망 없음, validate가 더 안전.

## 결과 (Consequences)
- **긍정**: 스키마 변경 이력·롤백 추적, 부팅 시 스키마 정합성 자동 검증, 시크릿 유출 표면 축소.
- **부정**: 손수 작성 마이그레이션의 enum 값 집합을 **알파벳순**으로 둬야 Hibernate MySQL 네이티브 ENUM 매핑의 validate 기대값과 일치(Boot 3.5 함정). 모든 스키마 변경에 마이그레이션 작성 필요.
