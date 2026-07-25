# commerce-api 아키텍처

> 패션 셀렉트샵(다중 셀러 마켓플레이스) 커머스 백엔드의 설계 레퍼런스.
> 실제 코드(컨트롤러·서비스·엔티티·마이그레이션·테스트)를 정독해 작성·검증한 문서다.
> README가 "무엇을·왜"의 요약이라면, 이 문서는 **도메인·데이터·핵심 흐름의 깊은 근거**를 담는다.

---

## 1. 개요

**Spring Boot 3.5.14 기반, 모노레포(backend + frontend) 패션 셀렉트샵 백엔드 클론.**

- **목적**: .NET 응용프로그램 개발자의 **백엔드 전환 포트폴리오**. 도메인형 패키지·애그리거트 경계·JWT 스테이트리스 인증·동시성 제어·결제/정산/대사·이벤트 정합성 등 실무 패턴을 학습·시연한다.
- **컨셉**: 단일 쇼핑몰이 아니라 **여러 브랜드(셀러)가 입점하는 셀렉트샵**. 그래서 "매출 ≠ 셀러 정산금"을 1급 시민으로 모델링하고, *결제 → 셀러별 정산 → PG 대사 → 정산금 지급*까지 백오피스를 구현한 것이 차별점이다.
- **현재 범위**: **18개 REST 도메인** + 이벤트 전용 `notification`(HTTP 없음) + 공통 `global` = **19개 도메인**. 마이그레이션 **V1~V36**, 테스트 **411개**.

> 개별 의사결정의 상세 근거·대안 비교는 ADR(`docs/private/adr/`, 로컬 전용)에 있으며, 이 문서가 그 공개 요약본 역할을 한다.

---

## 2. 기술 스택 & 버전

| 영역 | 기술 | 버전/비고 |
|---|---|---|
| 언어 | Java | 21 (toolchain) |
| 프레임워크 | Spring Boot | **3.5.14** (4.0 미사용 — 의도적 고정, ADR-0001) |
| 빌드 | Gradle (Wrapper) | — |
| 보안/인증 | Spring Security + JWT(jjwt) | 0.12.6, HS256, **httpOnly 쿠키** |
| ORM | Spring Data JPA + Hibernate | **`ddl-auto: validate`** (스키마는 Flyway가 소유) |
| 동적 쿼리 | **QueryDSL** | 5.1.0 (jakarta) |
| DB(운영/로컬) | MySQL 8.0 | Docker, host **3307** → container 3306 |
| DB(테스트) | H2 in-memory | `MODE=MySQL`, `create-drop`, Flyway 미사용 |
| 마이그레이션 | **Flyway** | V1~V36, `baseline-on-migrate` |
| API 문서 | springdoc-openapi | Swagger UI |
| 메시징 | 트랜잭셔널 아웃박스 + **RabbitMQ** | opt-in(`outbox.publisher`), 기본 in-process |
| 캐시 | Spring Cache | **Caffeine**(기본) / **Redis**(opt-in) / NoOp 토글 |
| 분산 락 | 포트 + 어댑터 | NoOp(기본) / Redis(DIY) / **Redisson** 토글 |
| 관측성 | Micrometer · Actuator | → **Prometheus + Grafana** (opt-in 프로파일) |
| 부하 테스트 | **k6** | 캐시 처리량 · 쿠폰 동시성 시나리오 |
| 기타 | Lombok · Validation · JUnit5 · GitHub Actions(CI) | — |

좌표: groupId `com.commerce`, artifactId `commerce-api`, 루트 패키지 `com.commerce.api`.

---

## 3. 모노레포 & 패키지 구조

```
commerce-api/ (repo root)
├── backend/    Spring Boot 앱 (모든 gradle/docker 명령은 여기서)
│   ├── src/main/java/com/commerce/api/   (도메인형 패키지 — §4)
│   ├── src/main/resources/db/migration/  (Flyway V1~V36)
│   ├── monitoring/    (Prometheus/Grafana 설정·대시보드·alert.rules)
│   ├── load-test/     (k6 시나리오)
│   └── Dockerfile · docker-compose.yml · run.ps1
├── frontend/   Next.js 15 / React 19 / TS (App Router)
├── docs/       architecture.md · dev-log · deploy.md · ADR(private)
└── .claude/    skills · settings
```

### 패키지 (도메인형)

```
com.commerce.api
├── member · auth                      회원 · JWT 쿠키 인증
├── product · category · brand         카탈로그(상품/SKU/이미지 · 2단계 카테고리 · 브랜드)
├── cart · address · order · payment   장바구니 · 주소록 · 주문/체크아웃 · 결제(다중 PG)
├── seller · settlement                셀러 · 정산/지급(payout)/대사(reconciliation)
├── coupon                             쿠폰/프로모션/선착순
├── review · wishlist                  리뷰 · 위시리스트
├── activity · recommendation          행동 로그 · 개인화 추천(나를위한/함께산)
├── dashboard · monitoring             운영 KPI · 캐시 모니터링
├── notification                       이벤트 소비(HTTP 컨트롤러 없음)
└── global   config · common · exception · security · lock · ratelimit · events
```

각 도메인은 `controller · service · repository · entity · dto`로 일관 분리한다.

| 계층 | 책임 |
|---|---|
| Controller `@RestController` | 라우팅, `@Valid`, `ApiResponse` 래핑 |
| Service `@Service @Transactional` | 비즈니스 로직, 트랜잭션 경계(기본 readOnly), 소유권 검사(`SecurityUtil`) |
| Repository `JpaRepository` (+ QueryDSL `…Impl`) | 데이터 접근, 동적 쿼리 |
| Entity | 도메인 모델 + 도메인 로직(애그리거트 루트) |
| DTO(record) | 불변 요청/응답 계약, `from()` 매핑 중앙화 |

> **계층 흐름**: Controller → Service → Repository → Entity. DTO(record)는 경계에서만. (ASP.NET Core의 Controller/Service/Repository + DI와 동형)

---

## 4. 보안 아키텍처

### 4.1 인증 — JWT httpOnly 쿠키 (ADR-0005)

브라우저 SPA(Next.js)를 위해 토큰을 **httpOnly 쿠키**로 운반한다(localStorage는 XSS로 탈취 가능). `AuthCookieManager`가 `ResponseCookie`로 httpOnly + SameSite를 설정한다.

| | Access | Refresh |
|---|---|---|
| 운반 | httpOnly 쿠키 | httpOnly 쿠키 |
| 만료 | 30분 | 14일 |
| role claim | **있음**(인가용) | 없음 |
| jti(UUID) | 있음 | 있음 |
| 저장 | 무상태 | **`refresh_token` 테이블**(멤버당 1행, stateful) |
| 요청 인증 | 가능 | 불가(role claim 없음 → SecurityContext 미설정) |

- **회전(rotation)**: refresh 시 저장본과 정확히 일치해야 하고, 새 토큰 발급과 동시에 저장값을 in-place upsert(`findByMemberId` + `ifPresentOrElse`). 불일치/이미 회전됨 → 401. → refresh 토큰 1회용화, **재사용 탐지**.
- **principal = memberId(Long)**: 위변조 불가, DB 직매핑. `SecurityUtil.getCurrentMemberId()`(없으면 401) / `isAdmin()`.
- 세션 `STATELESS`, CSRF/Basic/formLogin 비활성. `JwtAuthenticationFilter`(OncePerRequestFilter)를 `UsernamePasswordAuthenticationFilter` 앞에 등록.

### 4.2 인가 — 경로 기반 (SecurityConfig 단일 출처)

인가는 전부 `SecurityConfig`의 **경로 기반 규칙**으로 한다(코드에 `@PreAuthorize` 0개). 자원별 소유권("내 주문 또는 ADMIN", "작성자만", "구매자만 리뷰")은 **서비스 계층에서** `SecurityUtil`로 강제한다.

| 경로 | 인가 |
|---|---|
| `POST /api/auth/login·refresh·logout` · `POST /api/members`(가입) | permitAll |
| `GET /api/products/**` · `/api/categories` · `/api/brands` · 리뷰 조회 | permitAll |
| `/swagger-ui/**` · `/v3/api-docs/**` · `/actuator/health` · `/actuator/prometheus` | permitAll |
| `/api/coupons/**` · `/api/sellers/**` · `/api/settlements/**` · `/api/payouts/**` · `/api/reconciliations/**` · `/api/dashboard` · `/api/monitoring/**` · 카탈로그 쓰기 | **hasRole(ADMIN)** |
| `/api/seller/**` (셀러 셀프 콘솔) | **hasRole(SELLER)** |
| 그 외 | **authenticated** (`anyRequest`) |

- **역할(Role)**: USER · SELLER · ADMIN. 가입은 USER, SELLER/ADMIN은 직접 부여(`member.seller_id`로 셀러 콘솔 스코프).
- **비밀번호**: `BCryptPasswordEncoder`(salt 내장). 소셜 유저는 password null(§7 OAuth prep).
- **로그인 레이트 리밋**: 이메일당 5회/분 초과 시 인증 평가 *전에* 429(`global/ratelimit`, `RateLimitIntegrationTest`).

---

## 5. 데이터 모델

### 5.1 애그리거트 경계 — ID 참조 vs 객체 연관 (ADR-0002)

```
애그리거트 내부  ══ @OneToMany cascade/orphanRemoval (FK + 객체연관)
애그리거트 간    ── Long ID 참조 (FK 객체 없음, 필요 시 findAllById 배치 enrich)

Order ══(N) OrderItem        Cart ══(N) CartItem        Product ══(N) ProductOption / ProductImage
Order ──memberId──> Member   OrderItem ──productId/sellerId(스냅샷)──> Product/Seller
Payment ──orderId──> Order   SettlementEntry ──paymentId/sellerId──> Payment/Seller
```

- 애그리거트를 넘는 `@ManyToOne`을 쓰지 않는다 → 결합도↓·경계 명확·불필요 로딩 회피. 이름은 조회 시점에 `findAllById` 배치로 enrich(N+1 회피).
- **실제 FK 제약은 애그리거트 내부에만**: `product_option.product_id`, `product_image.product_id`, `cart_item.cart_id`, `order_item.order_id`.
- **돈은 `bigint`(원 단위)**, 비율·점수는 `double`. enum은 `@Enumerated(STRING)` + 마이그레이션 enum 값을 **알파벳순**으로 둬 Hibernate `validate`와 일치(Boot 3.5 트랩, ADR-0006).
- 모든 엔티티 `BaseEntity`(createdAt 불변 / updatedAt) 상속.

### 5.2 주요 테이블

| 테이블 | 도메인 | 목적 | 핵심 컬럼/제약 |
|---|---|---|---|
| `member` | member/auth | 회원(로컬+소셜), 역할 기반 | email UNIQUE · role(USER/SELLER/ADMIN) · provider/provider_id · seller_id |
| `refresh_token` | auth | 멤버당 refresh 토큰 | member_id UNIQUE |
| `category` / `brand` | product | 2단계 카테고리 / 브랜드(셀러 소유) | name UNIQUE · category.parent_id · brand.seller_id |
| `product` | product(루트) | 판매 상품 + 비정규화 신호 카운터 | brand_id · category_id · status · image_url · rating_count/sum · wishlist_count |
| `product_option` | product(자식) | **SKU**(사이즈+재고) + 낙관적 락 | FK→product · `version` |
| `product_image` | product(자식) | 이미지 갤러리 | FK→product · sort_order |
| `cart`/`cart_item` | cart | 멤버당 장바구니 | cart.member_id UNIQUE · option_id |
| `orders` | order(루트) | 주문 헤더: 상태머신·배송 스냅샷·쿠폰 스냅샷 | status(PENDING/PAID/SHIPPING/DELIVERED/CANCELLED) · 배송 컬럼 · discount/coupon 스냅샷 |
| `order_item` | order(자식) | 라인: 가격·이름·사이즈 + brand/seller **스냅샷** | FK→orders · status(ACTIVE/CANCELLED, 부분환불축) |
| `shipment` / `shipment_status_history` | order(자식, #1) | **셀러별 배송 단위**(상태·송장) + 전이 이력 | order_id · seller_id(null=플랫폼) · status(PAID/SHIPPING/DELIVERED/CANCELLED) · `version` · courier/tracking. `orders.status`는 이들의 rollup 파생 |
| `address` | member | 저장 배송지 | member_id · is_default |
| `payment` | payment | 주문당 결제(다중 PG·부분환불) | order_id(ID참조) · idempotency_key UNIQUE · provider · refunded_amount |
| `settlement_entry` | settlement | **(결제 × 셀러)** 정산: 수수료·플랫폼수수료·할인 배분·지급 연결 | payment_id · seller_id · payout_id · gross/fee/net/platform_fee · fee_rate · provider |
| `mismatch` | reconciliation | PG↔우리 대사 불일치 + 예외 큐 | pg_transaction_id · type · status(OPEN/RESOLVED/IGNORED) · provider |
| `outbox_event` | global/events | 트랜잭셔널 아웃박스(at-least-once) | status(PENDING/PUBLISHED/FAILED) · retry_count · next_attempt_at |
| `notification_log` | events | 멱등 소비 로그 | event_id UNIQUE |
| `seller` | seller | 입점사(플랫폼 테넌트) | name UNIQUE · commission_rate · status(ACTIVE/SUSPENDED) |
| `payout` | settlement | 셀러·기간별 정산금 지급 묶음 | seller_id · period_from/to · totals · status(PENDING/PAID) |
| `coupon` | coupon | 쿠폰 정의(공개/발급·한정수량) | code UNIQUE · discount_type · funded_by · issue_type · total_quantity/issued_count |
| `member_coupon` | coupon | 멤버 쿠폰 지갑 | member_id+coupon_id UNIQUE · status(UNUSED/USED) |
| `wishlist` | personalization | 위시 상품 | member_id+product_id UNIQUE |
| `activity_log` | personalization | 조회 이벤트 append-only | type(VIEW) · idx(member_id, created_at) |
| `recommendation` | personalization | 멤버별 "나를위한" 사전계산 | member_id+product_id UNIQUE · score |
| `product_cooccurrence` | personalization | "함께 산 상품" 사전계산 | reference_product_id+product_id UNIQUE · co_buy_count · score |

### 5.3 스키마 진화 (Flyway 46개, 에포크별)

| 에포크 | 마이그레이션 | 내용 |
|---|---|---|
| A 코어 커머스 | V1~V2 | 회원·카탈로그·주문·장바구니·SKU 베이스라인 / OAuth 필드 prep(소셜은 후속) |
| B 결제 | V3~V4 | payment 테이블(멱등키) / 주문 상태머신 ORDERED→PENDING·PAID |
| C 정산·대사 | V5~V7 | settlement_entry(payment UNIQUE) / mismatch / 예외 상태(OPEN/RESOLVED/IGNORED) |
| D 아웃박스·이벤트 | V8~V10 | outbox_event / notification_log(event_id UNIQUE) / 백오프(next_attempt_at) |
| E 다중 PG | V11~V13 | payment·settlement·mismatch에 provider·fee_rate 컬럼 |
| F 리뷰·이미지·주소·배송 | V14~V17 | image_url / review(+평점 카운터) / address / 주문 배송 스냅샷 |
| G 셀러 정산 | V18~V24 | seller / brand·order_item 셀러 스냅샷 / (payment×seller) 분해 / SELLER 역할 / payout / 부분환불 |
| H 쿠폰 | V25~V27 | coupon(funded_by) / 정산 할인 배분 / issue_type + member_coupon 지갑 |
| I 개인화 | V28~V31 | wishlist / activity_log / recommendation / product_cooccurrence |
| J 어드민·카테고리·배송상태 | V32~V35 | product_image 갤러리 / category.parent_id / 배송 상태 enum / 쿠폰 한정수량 |
| K 인덱스 | V36 | 애그리거트 FK 회피로 안 잡힌 필터 컬럼에 보조 인덱스 |
| L 감사·이력 | V37~V41 | audit_log / 주문 멱등키 / order_status_history+송장 / 돈경로·정산일 인덱스 |
| M 할인가·재고예약 | V42~V44 | product.original_price(#5) / stock_reservation+reserved(#2) / orders.version(낙관락) |
| N 멀티셀러 배송(#1) | V45~V46 | shipment+shipment_status_history(셀러별 배송축) / orders 송장 컬럼 DROP(shipment로 이전) |

---

## 6. 핵심 도메인 흐름 (깊은 설계)

### 6.1 주문 → 결제 → 재고 (ADR-0003·0008)

```
체크아웃  POST /api/orders/checkout → 주문 PENDING (가격/배송지 스냅샷, 재고 차감 ❌)
결제      POST /api/payments → PaymentGatewayRouter.approve
  성공 → [한 트랜잭션] 재고 차감(@Version) + Order PAID + Payment PAID
  실패 → Payment FAILED, Order PENDING 유지(재결제 가능)
취소      (PAID였으면) 재고 복원 + Order/Payment CANCELLED   ※ SHIPPING 이후 취소 차단(409)
```

- **재고 차감 시점 이동**: 주문 생성이 아니라 **결제 승인 시점**. 같은 SKU 동시 결제는 `ProductOption.version` 낙관적 락으로 충돌 감지.
- **재시도 빈 분리**: `OrderService`(@Retryable, OptimisticLockingFailureException, 3회·100ms 백오프) → `OrderProcessor`(@Transactional)에 위임. 같은 빈 self-invocation은 프록시를 안 거쳐 "롤백→새 트랜잭션 재시도"가 안 되므로, **별도 빈으로 분리**해 프록시 경유를 보장. 실재 재고부족은 재시도 없이 409.
- **멱등성**: `Payment.idempotencyKey`(UUID) UNIQUE. 같은 키 재요청은 재승인·재차감 없이 기존 결과 반환(네트워크 재시도·더블클릭 방어).

### 6.2 다중 PG 라우팅 (ADR-0010)

`PaymentGateway` 포트 + 2개 모의 어댑터(Toss/Kakao, `AbstractMockPaymentGateway`) + `PaymentGatewayRouter`. **3가지 전략**:

1. **클라이언트 지정** — 요청의 provider로 승인(대소문자 무시, 미지정/미지원 → 기본/400).
2. **페일오버**(`approveWithFailover`) — 비용 오름차순으로 PG를 순회, 다운/거절 시 다음 PG로. 실제 승인한 PG를 `Payment.provider`에 기록(환불을 그 PG로 라우팅).
3. **비용기반 AUTO** — `provider="AUTO"`면 수수료율이 가장 낮은 PG 선택.

> 수수료율은 `PaymentGateway.feeRate()` **단일 출처** — 라우팅과 정산이 같은 값을 공유(정산→결제 방향 의존). 검증: `PaymentGatewayRouterTest`(13).

### 6.3 셀러별 정산 (ADR-0011)

한 주문에 여러 셀러 상품이 섞이므로 정산은 **(결제 × 셀러)로 분해**한다. `SettlementService.run()`:

1. PAID 결제를 스캔 → 셀러별 gross 합산(귀속은 주문 시점 `order_item.seller_id` **스냅샷** 기준 — 나중에 브랜드를 재귀속해도 과거 정산 불변).
2. **PG 수수료**를 셀러별로 비례 배분(잔여 원 단위는 최대 셀러에 — largest remainder).
3. **플랫폼 수수료**(`Seller.commissionRate`) 차감 → `net = gross − fee − platform_fee (+ PLATFORM 부담 할인 환원)`.
4. `SettlementEntry` 생성. 멱등성은 `existsByPaymentId`(앱) — V24에서 부분환불 역분개 행을 허용하려 `(payment_id, seller_id)` UNIQUE를 제거했기 때문.
5. **payout**: 셀러·기간별로 `SettlementEntry`를 묶어 지급 단위(PENDING→PAID), 이중 지급은 `payout_id` 연결로 방지.

### 6.4 PG 대사 (Reconciliation, ADR 흐름)

우리 정산 장부와 **PG의 독립 장부**(모의 PG가 stateful ledger 보유)를 `pgTransactionId`로 조인. `ReconciliationService`가 각 거래를 **정상(MATCHED)과 4종 불일치**(`MISSING_IN_PG · MISSING_IN_OURS · AMOUNT_MISMATCH · STATUS_MISMATCH`)로 분류(예: "정산 후 환불 → STATUS_MISMATCH"가 자연 발생). `mismatch` 행으로 적재되는 건 불일치 4종이고, MATCHED는 요약 카운트.

- 셀러 분할 정산을 PG 거래 단위로 group-by-sum해 비교, **PG별(byProvider) 분해**.
- **일자 윈도우**(선택 `from/to`, 무인자=전체) — 정산일 기준, OPEN 삭제는 해당 윈도우 거래키로 한정(다른 날 OPEN 보존).
- 불일치는 예외 큐: `OPEN → RESOLVED/IGNORED`(사유). 재실행 시 이미 처리된 키는 다시 OPEN하지 않음. 검증: `ReconciliationServiceTest`(20).

### 6.5 트랜잭셔널 아웃박스 (ADR-0009)

결제완료 이벤트의 **이중 쓰기** 문제(DB 커밋과 메시지 발행 사이 크래시 → 이벤트 유실/유령)를 해결.

```
결제완료  PaymentCompletionRecorder.saveWithEvent
          → [한 로컬 트랜잭션] payment.markPaid + outbox_event INSERT(PENDING)
폴러      OutboxRelay(@Scheduled) → FOR UPDATE SKIP LOCKED로 PENDING 청크 claim
          → EventPublisher 포트로 발행 → PUBLISHED
소비      NotificationLog(event_id UNIQUE) → 멱등 소비(at-least-once 대비)
```

- `PaymentService.pay`는 의도적으로 `@Transactional`이 아님(낙관락 재시도 보존) → 아웃박스가 정합을 보강.
- **신뢰성(P2a)**: 지수 백오프(`next_attempt_at`, 2→4→8초) + 최대 재시도 후 dead-letter(FAILED) + 멀티 폴러 안전(SKIP LOCKED, MySQL 전용).
- **발행 포트**(`EventPublisher`): 기본 in-process, **opt-in으로 RabbitMQ**(`RabbitEventPublisher` + `@RabbitListener`, exchange `commerce.events`). 결제/폴러는 포트에만 의존 → 어댑터 교체 시 코드 변화 0. 검증: `OutboxProcessorTest`(6).

### 6.6 쿠폰 / 프로모션 (ADR-0012)

- **funded-by 회계**: `Coupon`은 4축(부담 PLATFORM/SELLER · 범위 sellerId · 발급 PUBLIC/ISSUED · 정액/정률+상한). 체크아웃은 **gross 보존** 후 `payableAmount = gross − discount`만 청구.
- **정산 배분**: 누가 할인을 부담하는지가 셀러 net과 플랫폼 마케팅 비용을 가르므로, 할인을 셀러 정산에 비례 배분하고 아이템별 유효가(`Order.discountShares`)를 단일 출처로 둬 **부분 취소 과환불 방지**.
- **선착순 한정수량**(ADR-0015 연계): `coupon.total_quantity/issued_count`. `POST /api/member-coupons/claim/{id}`는 **원자적 조건부 UPDATE**(`incrementIssuedCount` — 한도 내에서만 +1, DB 행 잠금) + `member_coupon` UNIQUE(1인 1장)로, **앱 락 없이** 초과 발급 0을 보장. 검증: `CouponClaimConcurrencyTest`(30명→정확히 10장).

### 6.7 개인화 추천 (배치)

- **신호**: `activity_log`(VIEW, append-only) + 위시리스트·구매(PAID 주문) 테이블.
- **나를 위한 추천**: 규칙 기반 배치(구매×3 / 위시×2 / 조회×1 → 카테고리·브랜드 선호 → top-10), `@Scheduled+@Transactional` 단일 메서드(self-invocation 회피), `recommendation` 사전계산.
- **함께 산 상품**: PAID 주문의 상품쌍을 `COUNT(DISTINCT order)`로 집계 → `product_cooccurrence`. 추천→상품 단방향을 위해 `RecommendationController`에 배치(상품 컨트롤러 아님).

### 6.8 멀티셀러 배송 — shipment 상태 축 (#1, ADR 흐름)

**문제**: 한 주문에 여러 셀러 상품이 섞이는데 배송 상태가 **주문 전체**(`orders.status`) 단위라, 셀러별 개별 출고를 표현 못 했다(한 셀러 출고가 주문 전체를 SHIPPING으로 올려 미출고 셀러 항목 취소까지 막음). → 배송 상태축을 **셀러별 shipment**로 내렸다(정산 타이밍은 PAID 즉시 유지 — 정산/대사/멱등키 무접촉).

**모델** (`shipment` = 셀러별 출고 묶음, V45):
- **grain = (order, sellerId)**. `sellerId=null`은 플랫폼 직매입 버킷. 항목 연결은 FK가 아니라 **(order, sellerId) 매칭**(`Shipment.belongsToSeller`) — `order_item` 스키마 불변.
- **생성 = 결제 팬아웃**: `Order.markPaid()`가 활성 항목을 sellerId로 묶어 shipment 1건씩(PAID) 생성. 전량취소 셀러는 활성 항목이 없어 shipment 없음(정산 활성-항목 기준과 정합).
- **`Order.status` = 저장된 rollup 파생값**: 각 shipment 전이 후 `recomputeStatusFromShipments`로 재계산. 규칙(활성=비취소 기준, **forward-only 단조**): 전부 취소→CANCELLED / 전부 DELIVERED→DELIVERED / 하나라도 출고 시작→SHIPPING / 전부 PAID→PAID. **저장 컬럼을 유지**해 PURCHASED 리더(리뷰자격·추천·대시보드 `countGroupByStatus`)·인덱스가 무변경 생존.
- **송장(courier/tracking)**은 주문이 아니라 각 shipment에(셀러별 개별 송장). `orders`의 잉여 컬럼은 V46에서 DROP(expand/contract).

**취소/환불 불변식** (돈경로 무손상의 핵심):
- **취소는 shipment-grain**: 출고 전(shipment PAID/미결제) 항목만 취소, 출고된 셀러 항목은 잔존(**부분 취소**). 셀러의 마지막 활성 항목이 취소되면 그 shipment도 CANCELLED → rollup.
- **재고 되돌리기는 항목 예약상태로** 판정(`StockReservationService.undoForOrderItem`): `CONSUMED`(결제 실차감)→실재고 복원 / `ACTIVE`(예약만)→해제. 전체 `Order.status`가 아니라 항목별 실차감 여부로 봐, 다른 셀러 출고로 주문이 PAID를 벗어나도 재고가 정확히 복원(셀러 재고 영구누락 차단).
- **부분환불 = 이번에 취소된 몫만**: `refundNow = (payment.amount − refundedAmount) − 취소후 남은 활성 실효가`. 출고된 셀러분 재환불 차단. 쿠폰 복원은 `status==CANCELLED`(전량취소)일 때만.
- **정산 역분개 보존**: 취소는 반드시 `OrderItem.status=CANCELLED`를 경유(정산 `reverseRefunds`가 활성-항목 기준). shipment만 취소하고 항목을 안 건드리면 과지급 → 금지.

**엔드포인트**:
- 셀러 `PATCH /api/seller/me/shipments/{id}/status` — 자기 shipment 전진(소유권 트랜잭션 내 검증, 남의 셀러·null 버킷 403). 응답은 **셀러 스코프**(`SellerShipmentResponse`: 내 shipment·내 항목·배송지만 — 타 셀러 품목/송장·구매자 식별자 비노출).
- ADMIN `PATCH /api/orders/{id}/shipments/{sid}/status`(셀러별/플랫폼 개별) + `PATCH /api/orders/{id}/status`(활성 shipment 일괄 전진, 기존 라우트 유지).
- **백필**: P2 이전 주문에 shipment 소급 생성 — `ShipmentBackfillWorker`가 주문별 락+재확인·개별 트랜잭션(대량 안전 + 동시 취소와 직렬화).

> 구현: 6-phase(스키마→팬아웃/백필→rollup/동시성→취소교차→인가→DROP) + **5렌즈 적대적 리뷰가 확정한 동시성 6종 교정(P7)**. 동시성 처방은 §7 참조. 검증: `ShipmentTest`·`ShipmentConcurrencyTest`(전진×취소·취소×취소 수렴)·`PaymentCancelConcurrencyTest`·`ShipmentAdvanceAuthTest`(IDOR).

---

## 7. 동시성 제어 — 세 가지 전략

| 문제 | 전략 | 이유 |
|---|---|---|
| **재고 초과판매** (낮은 경합, 도메인 규칙·메시지 필요) | `@Version` **낙관적 락** + 새 트랜잭션 재시도 | 비관적 락의 처리량 손해 회피, 충돌은 드물고 재시도로 흡수 |
| **선착순 쿠폰** (높은 경합, 단순 카운터) | **원자적 조건부 UPDATE** + UNIQUE | 한 문장으로 "한도 내에서만 발급" 직렬화 — 앱 락 불필요, DB가 펜싱 |
| **shipment 상태 rollup** (#1, 파생 상태 + 부작용) | **비관적 쓰기 락**(부모 주문 `findByIdForUpdate`, PESSIMISTIC_WRITE + READ_COMMITTED) | `Order.status`가 shipment rollup 파생인데 rollup write가 **조건부**(값 바뀔 때만)라, 서로 다른 자식(shipment/항목)을 동시에 바꾸는 두 tx가 각자 형제를 stale로 읽어 둘 다 "변화 없음"으로 커밋되는 lost update가 난다. 상태/원장 변경 **모든 경로**(전진·취소·ADMIN 일괄·백필)가 부모 주문을 같은 락으로 잡아 **부작용(PG 환불) 이전에** 직렬화 — 늦은 tx는 로드 시점에 막혀 형제의 최신 상태로 rollup을 재계산. 취소는 PG 환불이 있어 낙관락 재시도가 부적합(재시도=이중환불)이라 비관락 채택 |

> **왜 세 번째 전략인가**: 낙관락(재고)·원자 UPDATE(쿠폰)로 안 되는 케이스 — 파생 상태의 조건부 write + 외부 부작용(환불)이 겹친다. 적대적 리뷰가 "worker만 락, 취소·일괄·백필 무락"의 락 비대칭이 stale-sibling lost update를 냄을 확정 → 전 경로 비관락 통일로 교정. 검증: `ShipmentConcurrencyTest`(전진×취소→DELIVERED·취소×취소→CANCELLED 수렴)·`PaymentCancelConcurrencyTest`(두 환불 누적=결제액).

**분산 락**(`global/lock`, ADR-0015)은 포트 + 어댑터로 토글한다: NoOp(기본) / Redis DIY(`SET NX PX` + Lua 원자 해제) / Redisson(`RLock` 워치독). 쿠폰 발급의 *정합*은 위 DB 원자 UPDATE가 이미 보장하므로 분산 락은 **멀티 인스턴스 직렬화를 위한 advisory**이고, DB가 펜싱 백스톱 역할을 한다. `MemberCouponClaimService`가 이 포트로 claim 트랜잭션을 감싼다.

> `LockComparisonTest`의 통찰: 리스(lease) 기반 DIY 락은 작업(2초)이 리스(1초)보다 길면 상호배제가 깨져 동시성=2가 되고, Redisson 워치독은 리스를 자동 연장해 동시성=1을 유지한다. (로컬 Redis 없으면 `assumeTrue`로 skip)

---

## 8. 횡단 관심사 (global)

| 관심사 | 구현 |
|---|---|
| 감사 | `BaseEntity`(@MappedSuperclass) + JPA Auditing: createdAt(불변)/updatedAt 자동 |
| 공통 응답 | `ApiResponse<T>` {success, message, data} — 성공/검증오류/비즈니스예외/시스템오류 단일 형태 |
| 전역 예외 | `GlobalExceptionHandler`(@RestControllerAdvice): BusinessException→보유 HttpStatus / Validation·메시지 파싱·**타입 불일치**→400 / 그 외→500(로깅) |
| 비즈니스 예외 | `BusinessException`(+HttpStatus) → 도메인이 상태코드 결정 |
| 캐싱 | `CacheConfig`가 3개 매니저 빌드(Caffeine/Redis/NoOp), `@Cacheable`/`@CacheEvict` + 도메인 간 무효화(리뷰·위시 → 상품) |
| 레이트 리밋 | 로그인 이메일당 5회/분 → 초과 시 429(인증 평가 전) |
| 보안 헬퍼 | `SecurityUtil.getCurrentMemberId()`(없으면 401) / `isAdmin()` |
| API 문서 | springdoc — `/swagger-ui.html`, `/v3/api-docs` |

---

## 9. 운영 · 관측성

> **설계 철학**: Redis·RabbitMQ·Prometheus/Grafana는 **코드로 완성되어 있되 기본은 OFF/로컬**. 단일 인스턴스 데모에 외부 의존을 강제하지 않으면서, 스케일아웃 시 토글만으로 켜지는 이음새를 확보했다. (구현 ≠ 기본 활성)

### 9.1 관측성 (Prometheus + Grafana, ADR-0016) — opt-in 프로파일

- Micrometer → `/actuator/prometheus`(permitAll) 노출, `http.server.requests` 히스토그램 버킷 + SLO 버킷, Tomcat 스레드 metric.
- `docker compose --profile observability up` → Prometheus(:9090, 5초 스크레이프) + Grafana(:3001). `monitoring/grafana/dashboards/commerce.json` — **3계층(행) 10개 패널**(① 골든 시그널 RPS/지연 p50·p95·p99/에러율 ② 포화도 JVM 힙·Hikari·CPU·Tomcat ③ 도메인 캐시 히트율·선착순 claim 201/409/503).
- 별도 앱 레벨 `GET /api/monitoring/caches`(ADMIN) — 캐시 히트율 KPI를 어드민 콘솔에 노출(Prometheus와 별개).

### 9.2 부하 테스트 (k6) — 수동

- `cache-throughput.js` — 캐시 ON/OFF 비교(thresholds: 에러<1%, p95<800ms). 측정: 캐시로 RPS ~5.3×, p95 ~13× 개선.
- `coupon-claim.js` — 200 VU 버스트로 100장 쿠폰 claim, `claim_success ≤ 100`(초과 발급 0 증명), 락 모드(none/redis/redisson) 비교.

### 9.3 배포 준비 — prep 완료(실배포는 후속)

- `Dockerfile`(멀티스테이지, temurin 21 jdk→jre, `bootJar -x test`), `server.port=${PORT:8080}`, DB·CORS·쿠키 전부 env화(`app.cors.allowed-origins` / `app.cookie.*`).
- **CORS·쿠키 실배선**: `allowCredentials=true`(와일드카드 없음), httpOnly 항상, prod는 Secure=true + SameSite=None(크로스 도메인 로그인 트랩 문서화).
- `run.ps1`(로컬 전용 — `.env` 로드 후 dev 프로파일 시드로 bootRun).

### 9.4 CI (GitHub Actions)

push/PR(`dev`·`main`) 시 2잡: **backend**(JDK 21, `gradlew test`, H2 — 시크릿/MySQL 불필요) + **frontend**(Node 20, `npm ci` → `tsc --noEmit` → lint). 배포·이미지 빌드 스테이지는 없음.

---

## 10. 전체 API 엔드포인트

> 인가: public(permitAll) · authenticated(로그인) · ADMIN · SELLER. 소유권("내 것 또는 ADMIN" 등)은 서비스 계층에서 강제.

| 도메인 | 엔드포인트 |
|---|---|
| **auth** | `POST /api/auth/login`(public, 5/분) · `/refresh` · `/logout`(public) · `GET /api/auth/me`(auth) |
| **member** | `POST /api/members`(public 가입) · `GET /api/members/{id}`(auth) |
| **product** | `GET /api/products`(검색/필터/정렬) · `/feed`(커서) · `/{id}`(public) · `POST·PUT /api/products` · 옵션 `POST·PUT·DELETE` · `PATCH /{id}/status` · 이미지 `POST·DELETE`(ADMIN) |
| **category** | `GET`(public) · `POST·PUT·DELETE`(ADMIN, 2단계) |
| **brand** | `GET`(public) · `POST·PUT·DELETE` · `PUT /{id}/seller`(ADMIN) |
| **cart** | `POST·GET /api/carts/items|carts` · `PUT·DELETE /items/{optionId}`(auth) |
| **address** | `GET·POST /api/addresses` · `PUT·DELETE /{id}` · `PUT /{id}/default`(auth) |
| **order** | `POST /api/orders` · `/checkout` · `/coupon-preview` · `GET /api/orders`(내것) · `/{id}` · `POST /{id}/cancel` · `/items/{itemId}/cancel`(부분) · `GET /admin`·`PATCH /{id}/status`(ADMIN 일괄 배송) · `PATCH /{id}/shipments/{sid}/status`(ADMIN 셀러별/플랫폼 배송, #1) |
| **payment** | `POST /api/payments`(auth, 멱등키) |
| **coupon** | `POST /api/coupons` · `/{id}/issue` · `GET`(ADMIN) · `GET /api/member-coupons/me|claimable` · `POST /claim/{id}`(auth) |
| **seller** | `GET·POST·PUT /api/sellers` · `suspend·activate·owner`(ADMIN) · `GET /api/seller/me|settlements|summary|payouts|orders`(SELLER) · `PATCH /api/seller/me/shipments/{id}/status`(SELLER 자기 출고, #1) |
| **settlement** | `POST /run` · `GET` · `POST /reverse-refunds` · `GET /summary` · `POST /{id}/payout`(ADMIN) |
| **payout** | `POST /api/payouts` · `/{id}/pay` · `GET`(ADMIN) |
| **reconciliation** | `POST /run`(from/to) · `GET /mismatches` · `POST /{id}/resolve|ignore`(ADMIN) |
| **review** | `POST·GET /api/products/{id}/reviews`(쓰기=구매자) · `PUT·DELETE /api/reviews/{id}`(작성자/ADMIN) |
| **wishlist** | `POST·DELETE·GET /api/wishlist[/me|/{productId}]`(auth) |
| **activity** | `POST /api/activity/views`(auth) |
| **recommendation** | `GET /me`(auth) · `/products/{id}/together`(public) · `POST /run`·`/cooccurrence/run`(ADMIN) |
| **dashboard** | `GET /api/dashboard?days=`(ADMIN) |
| **monitoring** | `GET /api/monitoring/caches` · `POST /caches/{name}/evict`(ADMIN) |
| **notification** | (REST 없음 — 이벤트 소비 전용) |

---

## 11. 핵심 설계 결정 요약

1. **Spring Boot 3.5 고정** — 전환 학습 단계의 안정성·레퍼런스 우선(4.0 미사용).
2. **도메인형 패키지 + 애그리거트 간 ID 참조** — 모듈러 모놀리스, 경계 명확.
3. **스냅샷 vs 라이브 참조** — Order/OrderItem은 가격·이름·셀러 스냅샷(이력 보존), Cart는 조회 시점 enrich.
4. **돈은 long(원), enum은 STRING** — 부동소수점·ordinal 위험 회피.
5. **재고 동시성 = 낙관적 락 + 새 트랜잭션 재시도** (재시도 빈 분리로 프록시 경유 보장).
6. **JWT httpOnly 쿠키 + access/refresh 회전 + jti** — XSS·재사용 방어, principal=memberId.
7. **인가는 경로 기반(SecurityConfig), 소유권은 서비스 계층**.
8. **시크릿 12-factor + Flyway validate** — 스키마를 마이그레이션이 소유.
9. **동적 검색 = QueryDSL** — 타입 안전, 정산 집계에도 재사용.
10. **결제 = 포트-어댑터 + 멱등키 + 다중 PG 라우팅** — 무중단 교체, 수수료율 단일 출처.
11. **트랜잭셔널 아웃박스** — 이중 쓰기 해결, 백오프·DLQ·SKIP LOCKED.
12. **셀러별 정산(payment×seller) + 대사 예외 큐** — "매출 ≠ 셀러 정산금"을 1급으로.
13. **쿠폰 funded-by + 선착순 원자 UPDATE** — 회계 정합 + 초과 발급 0.
14. **비정규화 카운터(평점·위시)** — 원자적 `@Modifying`(`flushAutomatically=true`로 파생 delete flush 순서 버그 수정).
15. **캐시·분산락·관측성·메시징은 토글** — 코드 완성 + 기본 OFF/로컬, 스케일아웃 이음새 확보.
16. **테스트는 H2**(격리), 동시성/멱등성/페일오버/대사는 명시적 테스트로 증명.

---

## 12. 인증 확장: OAuth2 / 소셜 로그인 (설계 — 스키마 prep만)

로컬(email/password)에 더해 구글·카카오·네이버 소셜 로그인을 위한 설계. **유저 모델은 V2로 미리 대비**(provider/providerId/nullable password)했으나 **소셜 로그인 구현 자체는 후속**이다.

- **원칙**: 검증 주체(IdP)만 갈아끼우고, 인증 성공 *이후*에는 로컬과 동일하게 우리 JWT 쿠키를 발급 → 기존 인증/인가 파이프라인 전부 재사용.
- **식별자 = (provider, providerId)** (email은 제공자 측 변경 가능 → 보조 키). 단일 테이블 채택, 다중 연동 요구 시 `social_account` 분리로 확장.
- 계정 연동·email 유니크 정책(자동 연동 vs provider별 별개)은 구현 시 확정. 의존성 `spring-boot-starter-oauth2-client`, client-id/secret은 환경변수.

---

## 13. 알려진 한계 / 확장 지점

> 포트폴리오로서 **무엇이 의도적으로 미구현인지**를 명시한다("구현 ≠ 기본 활성", "설계 ≠ 운영").

- **실제 PG 미연동** — 모의 PG(stateful ledger)로 시뮬레이션. `PaymentGateway` 포트에 토스/포트원 어댑터 + 결제창·웹훅을 추가하면 교체(이음새 확보됨).
- **Alertmanager 미배선** — Prometheus alert 규칙 6개는 평가되어 `/alerts`에 뜨지만 **알림 채널(Slack/메일)은 없음**(후속). 관측성 스택 자체는 opt-in 프로파일로 구동.
- **Redis 캐시·분산 락 기본 OFF** — Caffeine(기본)/NoOp 락(기본). Redis/Redisson 코드·도커는 완비, `app.cache.provider`/`app.lock.provider` + `--profile redis`로 활성.
- **RabbitMQ opt-in** — 기본 발행은 in-process. `outbox.publisher` 토글 시 실제 브로커 사용(SKIP LOCKED는 MySQL 전용 → 런타임 검증, 단위 테스트는 H2라 제외).
- **실배포는 prep까지** — Dockerfile·env화·CORS/쿠키·CI 완료, 실제 클라우드 배포(IaC/플랫폼 설정)는 후속.
- **OAuth2/소셜** — 유저 모델 prep만(§12).
- **FE 갭** — 회원가입 페이지 없음(로그인만), 이미지 URL 입력 방식(업로드 없음), 셀러 콘솔은 정산 조회 전용, FE 자동 테스트 없음(tsc/lint + 브라우저 확인).
- **의도적 단순화** — `Payment` 엔티티가 `HttpStatus`(웹 개념)를 import(도메인 예외 + `@ExceptionHandler` 분리는 후속). 정산 `(payment_id, seller_id)` UNIQUE는 V24에서 역분개 허용 위해 제거(멱등성은 앱 `existsByPaymentId`).

> 최신 진행 이력은 [docs/dev-log.md](dev-log.md), 개별 결정의 상세 근거는 ADR(`docs/private/adr/`) 참고.
</content>
