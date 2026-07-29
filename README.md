# commerce-api · 패션 셀렉트샵 커머스 백엔드

> 여러 브랜드(셀러)를 입점시키는 **패션 셀렉트샵**을 모델로 한 백엔드 클론 프로젝트.
> .NET(ASP.NET Core) 응용프로그램 개발자가 **Java / Spring Boot 백엔드로 전환**하며, 단순 CRUD가 아니라
> **동시성·결제·정산·대사·이벤트 정합성** 같은 실서비스 문제를 의도적으로 다룬 학습·포트폴리오용 모노레포입니다.

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5.14-6DB33F?logo=springboot&logoColor=white">
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white">
  <img alt="QueryDSL" src="https://img.shields.io/badge/QueryDSL-5.1.0-0769AD">
  <img alt="Flyway" src="https://img.shields.io/badge/Flyway-53%20migrations-CC0200?logo=flyway&logoColor=white">
  <img alt="JWT" src="https://img.shields.io/badge/Auth-JWT%20httpOnly-000000?logo=jsonwebtokens&logoColor=white">
  <img alt="Next.js" src="https://img.shields.io/badge/Next.js%2015-React%2019%20TS-000000?logo=nextdotjs&logoColor=white">
  <img alt="Tests" src="https://img.shields.io/badge/tests-724%20passing-success">
</p>

---

## 📌 한눈에

C# / ASP.NET Core 실무 경험을 바탕으로 **Spring Boot 백엔드 설계를 직접 구현하며 익히는 것**이 목표입니다.
"회원 → 상품 → 장바구니 → 주문"의 기본기에서 멈추지 않고, **셀렉트샵(다중 셀러 마켓플레이스)** 컨셉을 택해
*결제 → 셀러별 정산 → PG 대사 → 정산금 지급* 까지 **돈이 흐르는 백오피스**를 끝까지 구현한 것이 핵심 차별점입니다.

- 🎯 **컨셉** — 브랜드 입점형 셀렉트샵. "매출 ≠ 셀러 정산금"을 1급 시민으로 모델링.
- 🧩 **방식** — 도메인형 패키지 · 테스트 주도(**724개**) · 모든 의사결정을 [개발 일지](docs/dev-log.md)와 ADR로 기록.
- 🏗 **구조** — `backend`(Spring Boot) + `frontend`(Next.js) **모노레포**.
- 📐 **규모** — **22개 도메인**(20개 REST + 이벤트 전용 notification·outbox) · **53개 Flyway 마이그레이션** · **724개 테스트**(instruction 커버리지 91.3%).

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
| **주문~결제 사이의 오버셀 구간** — 낙관적 락은 "결제 순간"만 지켜 그 전에 판 재고가 겹칠 때 | `stock_reservation` + `product_option.reserved` 카운터를 **원자적 조건부 UPDATE**(`stock-reserved >= q`일 때만 예약). 결제=소진 · 만료/취소=해제 | `StockReservationConcurrencyTest` — 재고 10에 30스레드 주문 → 정확히 10건 |
| **파생 상태의 lost update** — 멀티셀러 주문에서 배송 전진과 취소가 동시에 일어날 때 | `Order.status`가 shipment rollup 파생이라 조건부 write가 서로를 stale로 읽는다 → **상태·원장 변경 모든 경로가 부모 주문 비관락**으로 직렬화(예외 0) | `ShipmentConcurrencyTest`·`PaymentCancelConcurrencyTest` |
| **정산금 이중 지급** — 같은 셀러·기간의 지급 묶음을 동시에 만들 때 | 비잠금 조회 + setter를 **원자적 조건부 UPDATE**(`payout_id IS NULL`인 항목만 편입)로 교체 + 편입 수 검증(경합 시 409·롤백) | `PayoutConcurrencyTest` — 8스레드 → 정확히 1건, 이중지급 0 |
| **환불 정합** — 쿠폰 할인·배송비·부분취소·반품이 겹칠 때 과다환불 | 항목 **실효가**(소계−안분할인)를 환불·정산의 단일 출처로, 환불액 = `잔여 결제액 − 취소 후 남은 payable`. 배송비는 활성 항목이 남아 있으면 유지 | `ShippingFeeTest`·`ReturnRefundTest`·`OrderTest` |

---

## 🛠 기술 스택

| 구분 | 기술 |
|------|------|
| Language / Runtime | **Java 21** |
| Framework | **Spring Boot 3.5.14** (의도적으로 3.5 고정 · 4.0 미사용) |
| Persistence | Spring Data JPA · Hibernate · **QueryDSL 5.1.0**(타입 안전 동적 쿼리) |
| Database | **MySQL 8**(Docker) · 테스트는 **H2** 인메모리(격리) |
| Migration | **Flyway**(V1~V53, `ddl-auto: validate`) |
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

## 🗺 시스템 한눈에 — 22개 도메인

```
스토어프론트            주문/결제                    백오피스 (돈의 흐름)
├ product  상품/검색     ├ cart     장바구니(게스트)   ├ seller        셀러(입점사)·콘솔
├ category 2단계 카테고리 ├ order    주문/셀러별 배송   ├ settlement    셀러별 정산·지급·대사
├ brand    브랜드        ├ payment  결제(다중 PG)      ├ dashboard     운영 KPI
├ review   구매자 리뷰    ├ returns  반품/교환          └ audit         변경 감사 로그
├ wishlist 위시리스트     └ address  배송지 주소록
├ coupon   쿠폰/선착순
├ recommendation 추천    인증/공통                     이벤트/관측
└ activity 행동 로그      ├ auth   JWT·소셜 로그인       ├ notification  인박스(이벤트 소비)
                        ├ member 회원                  └ monitoring   캐시 KPI
역할(Role): USER · SELLER · ADMIN   └ global 설정/예외/시큐리티·아웃박스
```

각 도메인은 `controller · service · repository · entity · dto`로 일관 분리하고,
**애그리거트 간 참조는 객체 연관 대신 `Long` ID로만** 연결합니다(모듈러 모놀리스 → 추후 MSA 분리 용이).
→ ASP.NET Core의 Controller/Service/Repository 계층 분리 + DDD-lite 경계.

---

## ✨ 핵심 기능

- **카탈로그 / 검색** — QueryDSL 동적 필터(키워드·가격대·카테고리·브랜드·사이즈·세일) + 정렬(최신/가격/평점/찜/할인율), 커서 기반 무한 스크롤 피드
- **상품 옵션(SKU)** — 사이즈 단위 옵션으로 재고 관리(같은 상품 다른 사이즈 = 별개 항목), 색상은 별도 상품(셀렉트샵 모델). 정가/판매가로 %OFF 노출
- **장바구니** — 비로그인도 담기는 **게스트 카트**(httpOnly 토큰 쿠키) → 로그인 시 회원 카트로 **수량 합산 병합**
- **주문 / 체크아웃** — 배송지 스냅샷, 쿠폰 미리보기·적용, **재고 예약(TTL)로 오버셀 구간 제거**(주문=예약 → 결제=소진 → 만료=해제), 배송비(정액+무료임계), 체크아웃 멱등키
- **멀티셀러 배송** — 한 주문에 셀러가 섞이면 결제 시점에 **셀러별 shipment로 팬아웃**, 주문 상태는 shipment rollup 파생. 셀러는 자기 건만 출고 전진(IDOR 차단)
- **결제** — 모의 PG **포트-어댑터** + 다중 PG 라우팅(페일오버·비용기반) + 멱등키(중복 결제 방지) + 부분 취소/환불 + 취소·환불 사유 taxonomy
- **반품 / 교환** — DELIVERED 이후의 역방향 워크플로(요청→승인→수거→검수→환불/교환). 환불은 **검수 확정 후 실효가**, 교환은 옵션 스왑(revenue-neutral)
- **셀러 정산** — (결제 × 셀러) 분해, 수수료·할인 비례 배분, 정산금 지급 배치(payout), PG 대사·예외 처리, 환불 역분개
- **쿠폰 / 프로모션** — 정액/정률, 플랫폼/셀러 부담(funded-by) 회계, 공개/발급형, **선착순 한정수량(동시성 제어)**
- **알림 인박스** — 아웃박스 이벤트를 구매자·셀러 인박스로(**1 이벤트 → N 수신자 fan-out**, 복합 멱등키), 헤더 벨·안읽음 뱃지
- **개인화 추천** — 행동 로그 기반 "나를 위한 추천" + co-occurrence "함께 산 상품" + 최근 본 상품
- **인증** — JWT httpOnly 쿠키(access/refresh 회전) + **구글·카카오 소셜 로그인**(provider별 opt-in)
- **운영 콘솔(FE)** — 상품/카테고리/브랜드 CRUD, 주문/배송, 정산/지급/대사, 쿠폰, 회원·권한, **감사 로그(CSV)**, 재고 임박 리포트, 대시보드(순매출 추이·캐시 히트율)
- **셀러 콘솔(FE)** — 내 주문·출고 전진·반품 처리·정산/지급·알림 인박스(모두 자기 셀러 스코프)
- **API 문서** — Swagger UI 자동 생성

> 전체 엔드포인트 표·도메인별 책임은 [docs/architecture.md](docs/architecture.md) 참고.

---

## 🧠 기술적 의사결정 & 도전

> 포트폴리오의 본질 — "무엇을"보다 **"왜 그렇게 결정했는지"**. 각 항목의 상세 근거·대안 비교는
> [docs/architecture.md](docs/architecture.md)에 정리했습니다.

1. **Spring Boot 3.5 고정 (4.0 회피)** — 전환 학습 단계에선 신규 메이저의 깨짐·빈약한 레퍼런스보다 **자료가 풍부한 3.5 라인**이 합리적.
2. **도메인형 패키지 + 애그리거트 간 ID 참조** — 계층형 대신 도메인형, 애그리거트 경계를 넘는 `@ManyToOne` 금지 → 결합도↓·경계 명확·모듈러 모놀리스.
3. **재고 동시성 = 3단 방어** — ①주문 시 **예약**(원자적 조건부 UPDATE로 `stock-reserved` 확인, 오버셀 구간 제거) ②결제 시 차감은 `@Version` 낙관적 락 + *트랜잭션 밖* 재시도(셀프 인보케이션 회피) ③TTL 만료 배치가 미결제 예약 회수. "언제 파느냐"를 결제 순간에서 **주문 순간**으로 앞당긴 것이 핵심.
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
17. **멀티셀러 상태 단위 = shipment** — "주문 하나에 상태 하나"로는 셀러별 출고를 표현할 수 없다 → 셀러별 배송 단위를 만들고 `Order.status`는 **rollup 파생값으로 저장**(기존 PURCHASED 리더·인덱스 무변경 생존). 파생 상태 + 외부 부작용(환불) 조합이라 동시성은 **부모 주문 비관락으로 통일**(낙관락 재시도 = 이중 환불).
18. **반품/교환 = 별도 애그리거트 + 상태머신** — 주문에 플래그를 더하는 대신 `ReturnRequest`가 전이를 엔티티로 강제. 환불은 **검수 확정 후**(flip-before-PG로 실패 시 전체 롤백), 교환은 옵션 스왑이라 매출 중립.
19. **알림 = 아웃박스 재사용 + 복합 멱등키** — 새 인프라 없이 기존 이벤트를 인박스로 소비. **1 이벤트 → N 수신자**(멀티셀러 fan-out)를 위해 멱등키를 `(event_id, recipient_type, recipient_id)` 복합으로 설계 — event_id 단독이면 둘째 셀러 알림이 막힌다.
20. **데모 데이터도 코드로** — 카탈로그·셀러·계정·리뷰까지 시드가 만든다(`app.demo-seed.enabled`). 어떤 DB에서도 같은 데모가 재현되고, "배포했더니 빈 상점" 문제가 구조적으로 사라진다.

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

**데모 데이터 · 체험 계정**

기동하면 시드가 빈 DB를 **바로 둘러볼 수 있는 상태**로 만듭니다(dev 프로파일 기본 ON — `app.demo-seed.enabled`).
상품 60종(세일·품절·재고임박·판매중지 섞임)·브랜드/셀러·리뷰·찜·멀티셀러 주문까지 채워지고, 재기동해도 중복되지 않습니다.

| 계정 | 비밀번호 | 볼 수 있는 것 |
|---|---|---|
| `buyer@commerce.com` · `demo1~3@commerce.com` | `demopass1234` | 주문·리뷰·찜·알림 벨 |
| `seller1@commerce.com` (메종클레이) · `seller2@` (노드폼컴퍼니) | `demopass1234` | 셀러 콘솔 — 내 주문·출고·반품·정산 |
| `admin@commerce.com` | `demopass1234` (로컬만) | 어드민 — 대시보드·정산/대사·감사 로그 |

> ⚠️ 어드민 계정은 **로컬에서만** 자동 생성됩니다. 공개 배포에서는 `APP_DEMO_SEED_ADMIN_PASSWORD`를 명시할 때만
> 만들어집니다 — 누구나 아는 관리자 계정이 열려 있으면 상품 삭제·권한 변경까지 가능하기 때문입니다.

---

## 🧪 테스트

```bash
cd backend
./gradlew test
```

- **724개** — 단위(서비스/엔티티) · 웹 슬라이스(`@WebMvcTest`) · 리포지토리(`@DataJpaTest`) · 통합(`@SpringBootTest`) · **동시성** 테스트
- 테스트 DB는 **H2 인메모리**(운영 MySQL과 독립, Flyway 미사용)
- 동시성·멱등성·페일오버·대사 분류 등 **핵심 로직은 명시적 테스트로 증명** (위 "진짜 문제" 표 참고)
- CI: GitHub Actions — push/PR(`dev`·`main`) 시 백엔드 `gradlew test`(H2) + 프론트 `tsc`/lint

---

## 📚 더 보기

- 🏛 **[아키텍처 설계 근거 (docs/architecture.md)](docs/architecture.md)** — 도메인·데이터 모델·핵심 흐름·동시성·운영까지 깊은 기술 레퍼런스
- 📓 [개발 일지 (의사결정·문제해결 기록)](docs/dev-log.md)
- 🤝 [기여 가이드 · Git 워크플로 (CONTRIBUTING.md)](CONTRIBUTING.md)
