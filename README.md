# commerce-api · 패션 셀렉트샵 커머스 백엔드

> 여러 브랜드(셀러)를 입점시키는 **패션 셀렉트샵**을 모델로 한 백엔드 클론 프로젝트.
> .NET(ASP.NET Core) 응용프로그램 개발자가 **Java / Spring Boot 백엔드로 전환**하며, 단순 CRUD가 아니라
> **동시성·결제·정산·대사·이벤트 정합성** 같은 실서비스 문제를 의도적으로 다룬 학습·포트폴리오용 모노레포입니다.

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5.14-6DB33F?logo=springboot&logoColor=white">
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white">
  <img alt="QueryDSL" src="https://img.shields.io/badge/QueryDSL-5.1.0-0769AD">
  <img alt="Flyway" src="https://img.shields.io/badge/Flyway-36%20migrations-CC0200?logo=flyway&logoColor=white">
  <img alt="JWT" src="https://img.shields.io/badge/Auth-JWT%20httpOnly-000000?logo=jsonwebtokens&logoColor=white">
  <img alt="Next.js" src="https://img.shields.io/badge/Next.js%2015-React%2019%20TS-000000?logo=nextdotjs&logoColor=white">
  <img alt="Tests" src="https://img.shields.io/badge/tests-411%20passing-success">
</p>

---

## 📌 한눈에

C# / ASP.NET Core 실무 경험을 바탕으로 **Spring Boot 백엔드 설계를 직접 구현하며 익히는 것**이 목표입니다.
"회원 → 상품 → 장바구니 → 주문"의 기본기에서 멈추지 않고, **셀렉트샵(다중 셀러 마켓플레이스)** 컨셉을 택해
*결제 → 셀러별 정산 → PG 대사 → 정산금 지급* 까지 **돈이 흐르는 백오피스**를 끝까지 구현한 것이 핵심 차별점입니다.

- 🎯 **컨셉** — 브랜드 입점형 셀렉트샵. "매출 ≠ 셀러 정산금"을 1급 시민으로 모델링.
- 🧩 **방식** — 도메인형 패키지 · 테스트 주도(**411개**) · 모든 의사결정을 [개발 일지](docs/dev-log.md)와 ADR로 기록.
- 🏗 **구조** — `backend`(Spring Boot) + `frontend`(Next.js) **모노레포**.
- 📐 **규모** — **19개 도메인**(18개 REST + 이벤트 전용 notification) · **36개 Flyway 마이그레이션** · **411개 테스트**.

> 깊은 설계 근거·다이어그램은 **[docs/architecture.md](docs/architecture.md)** 한 문서에 정리되어 있습니다.

---

## 🔥 이 프로젝트가 다루는 "진짜" 문제

포트폴리오의 핵심은 기능 개수가 아니라 **실서비스에서 마주치는 어려운 문제를 어떻게 풀었는가**입니다.

| 문제 | 어떻게 풀었나 | 검증 |
|------|--------------|------|
| **재고 초과판매(oversell)** — 같은 SKU에 동시 주문이 몰릴 때 | `ProductOption`(SKU) 단위 **낙관적 락(@Version)** + *실패 트랜잭션 밖* 새 트랜잭션 재시도(`@Retryable`) | `OrderConcurrencyTest` — 재고 10에 20스레드 결제 → 초과판매 0 |
| **선착순 한정 쿠폰 폭주** — 수천 명이 동시에 100장 쿠폰을 집을 때 | **원자적 조건부 UPDATE**(한도 내에서만 +1, DB 행 잠금) + `member_coupon` UNIQUE. 앱 락 없이 정합 | `CouponClaimConcurrencyTest` — 30명 동시 → 정확히 10장 발급 |
| **이중 쓰기(dual-write)** — DB 커밋과 이벤트 발행 사이 크래시 | **트랜잭셔널 아웃박스** — 결제완료와 `outbox_event`를 한 트랜잭션에 기록, 폴러가 발행(at-least-once) + 지수 백오프 + dead-letter | `OutboxProcessorTest` — 백오프 2→4→8초, 최대 재시도 후 FAILED |
| **매출 ≠ 셀러 정산금** — 한 주문에 여러 셀러 상품이 섞일 때 | 결제를 **(결제 × 셀러)로 분해** — 셀러별 gross 합산, PG 수수료·플랫폼 수수료·쿠폰 할인을 비례 배분(잔액은 최대 셀러에) | `SettlementServiceTest`(14) |
| **PG 정산 내역 대사** — 우리 장부와 PG 장부가 어긋날 때 | `pgTransactionId`로 조인 → 정상(MATCHED) + **4종 불일치 분류**(금액/상태/한쪽 누락) + OPEN→RESOLVED/IGNORED 예외 큐 | `ReconciliationServiceTest`(20) |
| **PG 장애·수수료 최적화** | `PaymentGateway` 포트 + 라우터 — 클라이언트 지정 / 비용 오름차순 **페일오버** / **비용기반 AUTO** 3전략 | `PaymentGatewayRouterTest`(13) |

---

## 🛠 기술 스택

| 구분 | 기술 |
|------|------|
| Language / Runtime | **Java 21** |
| Framework | **Spring Boot 3.5.14** (의도적으로 3.5 고정 · 4.0 미사용) |
| Persistence | Spring Data JPA · Hibernate · **QueryDSL 5.1.0**(타입 안전 동적 쿼리) |
| Database | **MySQL 8**(Docker) · 테스트는 **H2** 인메모리(격리) |
| Migration | **Flyway**(V1~V36, `ddl-auto: validate`) |
| Security | Spring Security · **JWT**(jjwt 0.12.6) · **httpOnly 쿠키** · access/refresh 회전 |
| Messaging | 트랜잭셔널 아웃박스 · **RabbitMQ**(opt-in, 기본은 in-process) |
| Caching | Spring Cache — **Caffeine**(기본) / **Redis**(opt-in) / NoOp 토글 |
| Observability | Actuator · Micrometer → **Prometheus + Grafana**(opt-in 프로파일) |
| Load test | **k6**(캐시 처리량 · 쿠폰 동시성 시나리오) |
| Docs / Ops | springdoc-openapi(Swagger) · Lombok · Validation · GitHub Actions(CI) |
| Frontend | **Next.js 15**(App Router) · **React 19** · TypeScript · Tailwind |

> **설계 철학**: Redis·RabbitMQ·Prometheus는 모두 **코드로 완성되어 있되 기본은 OFF/로컬**입니다.
> 단일 인스턴스 데모에 외부 의존을 강제하지 않으면서, 스케일아웃 시 **토글만으로 켜지는 이음새**를 미리 확보했습니다.

---

## 🗺 시스템 한눈에 — 19개 도메인

```
스토어프론트            주문/결제                   백오피스 (돈의 흐름)
├ product  상품/검색     ├ cart     장바구니         ├ seller        셀러(입점사)
├ category 2단계 카테고리 ├ order    주문/체크아웃     ├ settlement    셀러별 정산
├ brand    브랜드        ├ payment  결제(다중 PG)     ├ payout        정산금 지급 배치
├ review   구매자 리뷰    └ address  배송지 주소록      ├ reconciliation PG 대사
├ wishlist 위시리스트                                 └ dashboard     운영 KPI
├ coupon   쿠폰/선착순
├ recommendation 추천    인증/공통                    이벤트/관측
└ activity 행동 로그      ├ auth   JWT 쿠키 인증        ├ outbox        아웃박스 발행
                        ├ member 회원                 ├ notification  이벤트 소비(HTTP 없음)
역할(Role): USER · SELLER · ADMIN   └ global 설정/예외/시큐리티  └ monitoring   캐시 KPI
```

각 도메인은 `controller · service · repository · entity · dto`로 일관 분리하고,
**애그리거트 간 참조는 객체 연관 대신 `Long` ID로만** 연결합니다(모듈러 모놀리스 → 추후 MSA 분리 용이).
→ ASP.NET Core의 Controller/Service/Repository 계층 분리 + DDD-lite 경계.

---

## ✨ 핵심 기능

- **카탈로그 / 검색** — QueryDSL 동적 필터(키워드·가격대·카테고리·브랜드·사이즈) + 정렬(최신/가격/평점/위시수), 커서 기반 무한 스크롤 피드
- **상품 옵션(SKU)** — 사이즈 단위 옵션으로 재고 관리(같은 상품 다른 사이즈 = 별개 항목), 색상은 별도 상품(셀렉트샵 모델)
- **주문 / 체크아웃** — 배송지 스냅샷, 쿠폰 미리보기·적용, 결제 시점 재고 차감, 배송 상태(PAID→SHIPPING→DELIVERED forward-only)
- **결제** — 모의 PG **포트-어댑터** + 다중 PG 라우팅(페일오버·비용기반) + 멱등키(중복 결제 방지) + 부분 취소/환불
- **셀러 정산** — (결제 × 셀러) 분해, 수수료·할인 비례 배분, 정산금 지급 배치(payout), PG 대사·예외 처리
- **쿠폰 / 프로모션** — 정액/정률, 플랫폼/셀러 부담(funded-by) 회계, 공개/발급형, **선착순 한정수량(동시성 제어)**
- **개인화 추천** — 행동 로그 기반 "나를 위한 추천" + co-occurrence "함께 산 상품"
- **운영 콘솔(FE)** — 상품/카테고리/브랜드 CRUD, 주문/배송, 정산/지급/대사, 쿠폰 발급, 대시보드(매출 추이·캐시 히트율)
- **API 문서** — Swagger UI 자동 생성

> 전체 엔드포인트 표·도메인별 책임은 [docs/architecture.md](docs/architecture.md) 참고.

---

## 🧠 기술적 의사결정 & 도전

> 포트폴리오의 본질 — "무엇을"보다 **"왜 그렇게 결정했는지"**. 각 항목의 상세 근거·대안 비교는
> [docs/architecture.md](docs/architecture.md)에 정리했습니다.

1. **Spring Boot 3.5 고정 (4.0 회피)** — 전환 학습 단계에선 신규 메이저의 깨짐·빈약한 레퍼런스보다 **자료가 풍부한 3.5 라인**이 합리적.
2. **도메인형 패키지 + 애그리거트 간 ID 참조** — 계층형 대신 도메인형, 애그리거트 경계를 넘는 `@ManyToOne` 금지 → 결합도↓·경계 명확·모듈러 모놀리스.
3. **재고 동시성 = 낙관적 락 + 새 트랜잭션 재시도** — 비관적 락의 처리량 손해 대신 `@Version`. 셀프 인보케이션 함정을 피하려 **재시도 빈을 분리**(프록시 경유).
4. **상품 옵션 = SKU** — 사이즈×색상 매트릭스의 복잡도 폭발 대신 단일 축(사이즈)으로 단순화. 재고·버전을 `ProductOption`으로 이전.
5. **JWT httpOnly 쿠키 + 회전** — localStorage(XSS 노출) 대신 httpOnly 쿠키, access/refresh 분리 + jti로 재사용 탐지.
6. **시크릿 12-factor + Flyway validate** — 운영 중 외부 노출 MySQL 침해를 겪고, 시크릿을 OS 환경변수로, 스키마를 마이그레이션으로 통제(`validate`).
7. **동적 검색 = QueryDSL** — 메서드명 쿼리 폭발·문자열 JPQL의 타입 위험 대신 컴파일 체크되는 동적 쿼리(C# LINQ 경험 전이).
8. **결제 = 포트-어댑터** — PG API 세부가 비즈니스 코어를 흔들지 않도록 `PaymentGateway` 포트로 격리(무중단 교체). 멱등키로 중복 결제 방지.
9. **트랜잭셔널 아웃박스** — `@TransactionalEventListener`(내구성 없음)·2PC(무거움) 대신, DB 커밋과 이벤트를 한 트랜잭션에 묶고 폴러가 발행.
10. **다중 PG 라우팅** — 단일 PG 고정 대신 라우터 + 3전략(클라이언트/페일오버/비용기반). 수수료율은 `PaymentGateway.feeRate()` 단일 출처(정산과 공유).
11. **셀러별 정산** — 주문 단위 단일 정산으로는 멀티 셀러 분할 불가 → (결제 × 셀러) 분해, 셀러 귀속은 주문 시점 **스냅샷**.
12. **쿠폰 funded-by** — gross 보존 후 할인을 정산에 비례 배분(누가 할인을 부담하는가를 회계의 3번째 축으로).
13. **비정규화 카운터** — 매 조회 COUNT/AVG 대신 평점·위시수를 `Product`에 비정규화, 원자적 `@Modifying` UPDATE로 갱신.
14. **캐싱 토글 (Caffeine/Redis)** — 부하 테스트로 효과 측정(p95 43→3.3ms, RPS 591→3,148). 단일 인스턴스엔 Caffeine, 스케일아웃엔 Redis.
15. **분산 락 포트 + DB 백스톱** — 쿠폰 발급의 정합은 DB 원자 UPDATE가 보장. 분산 락(Redis/Redisson)은 멀티 인스턴스 직렬화를 위한 **opt-in 토글**.
16. **관측성 = Prometheus + Grafana** — 인앱 차트 대신 표준 스택(opt-in 프로파일). 13패널 3계층(골든 시그널/포화도/도메인) 대시보드.

---

## 🚀 실행 방법

> 모든 백엔드 명령은 `backend/`에서 실행합니다.

```bash
cd backend

# 1) 환경변수 준비 — MYSQL_USER/PASSWORD, JWT_SECRET 등
cp .env.example .env            # Windows: copy .env.example .env

# 2) 인프라 기동 (MySQL + Adminer + RabbitMQ)
docker compose up -d

# 3) 애플리케이션 실행
./gradlew bootRun               # Windows(.env 자동 로드): ./run.ps1
```

| 항목 | 주소 |
|------|------|
| API 서버 | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| MySQL (Docker) | localhost:**3307** / db `commerce` |
| Adminer (DB 콘솔) | http://localhost:8081 |

**선택 프로파일 (스케일아웃·관측 시연용)**

```bash
docker compose --profile redis up -d           # Redis(캐시·분산락) 켜기
docker compose --profile observability up -d    # Prometheus(:9090) + Grafana(:3001)
```

> 기본 실행에는 외부 의존이 필요 없습니다. Redis·RabbitMQ·Prometheus는 `app.cache.provider` /
> `app.lock.provider` / `outbox.publisher` 토글과 compose 프로파일로 켭니다.

---

## 🧪 테스트

```bash
cd backend
./gradlew test
```

- **411개** — 단위(서비스/엔티티) · 웹 슬라이스(`@WebMvcTest`) · 리포지토리(`@DataJpaTest`) · 통합(`@SpringBootTest`) · **동시성** 테스트
- 테스트 DB는 **H2 인메모리**(운영 MySQL과 독립, Flyway 미사용)
- 동시성·멱등성·페일오버·대사 분류 등 **핵심 로직은 명시적 테스트로 증명** (위 "진짜 문제" 표 참고)
- CI: GitHub Actions — push/PR(`dev`·`main`) 시 백엔드 `gradlew test`(H2) + 프론트 `tsc`/lint

---

## 📚 더 보기

- 🏛 **[아키텍처 설계 근거 (docs/architecture.md)](docs/architecture.md)** — 도메인·데이터 모델·핵심 흐름·동시성·운영까지 깊은 기술 레퍼런스
- 📓 [개발 일지 (의사결정·문제해결 기록)](docs/dev-log.md)
- 🤝 [기여 가이드 · Git 워크플로 (CONTRIBUTING.md)](CONTRIBUTING.md)
</content>
</invoke>
