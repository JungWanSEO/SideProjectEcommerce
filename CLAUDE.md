# commerce-api

패션 커머스 백엔드 클론 — .NET 응용프로그램 개발자의 백엔드 전환 포트폴리오.

## 스택 (변경 시 사용자 확인 필수)
- Java 21 · Spring Boot **3.5.14** (⚠️ 4.0 금지 — 의도적 3.5 선택) · Gradle (Wrapper 사용)
- 의존성: Web · Data JPA · MySQL · Lombok · Validation · Actuator · H2(테스트) · Security · JWT(jjwt 0.12.6) · springdoc-openapi(Swagger) · QueryDSL(5.1.0:jakarta — 동적 쿼리)

## 모노레포 구조
- 루트는 모노레포: **`backend/`**(Spring Boot 앱), **`frontend/`**(예정), `docs/`·`CLAUDE.md`·`.claude/`(공통).
- 백엔드 관련 명령은 **`backend/`에서 실행**한다.

## 좌표 / 구조 (backend/)
- 루트 패키지 `com.commerce.api` (groupId `com.commerce`, artifactId `commerce-api`)
- **도메인형 구조**: `member` · `product` · `cart` · `order` · `auth` (각각 controller/service/repository/entity/dto) + `global`(config/exception/common/security)

## 명령어 (backend/ 에서)
- DB 실행 `docker compose up -d` (MySQL) · 실행 `./gradlew bootRun` · 빌드 `./gradlew build` · 테스트 `./gradlew test`
- DB: 앱은 MySQL(Docker), 테스트는 H2(`backend/src/test/resources/application.yml`)로 분리

## 작업 원칙
- 의미 있는 선택(버전·설정·구조)은 **먼저 묻고** 진행. 임의 결정 금지.
- 학습 목적 — 한 번에 다 찍어내지 말고 단계별로. .NET(ASP.NET Core) 비유 환영.
- **추가·문제·결정이 생길 때마다 `docs/dev-log.md`를 갱신한다** (살아있는 기록 습관).

## 더 읽을 것 (필요할 때만)
- 새 도메인 추가 → `add-domain` skill (`/add-domain`)
- 개발 일지 작성 → `dev-log` skill
- 아키텍처 근거 → `docs/architecture.md`
- Git 브랜치·PR·커밋 규칙 → `CONTRIBUTING.md` (main 보호 · 작업은 `feature/*`→PR로 dev 병합 · Conventional Commits)
