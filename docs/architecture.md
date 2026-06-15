# commerce-api 아키텍처

> 이 문서는 실제 코드를 정독해 작성·검증한 아키텍처 설명이다. (멀티 에이전트 정독 → 종합 → 적대적 검증)

## 1. 개요
**패션 커머스 백엔드 클론 (Spring Boot 3.5.14, 모노레포).**
- 목적: .NET 응용프로그램 개발자의 **백엔드 전환 포트폴리오**. 도메인형 패키지, JWT 스테이트리스 인증, 애그리거트 설계, 스냅샷 보존 등 실무 패턴 학습/시연.
- 현재 범위: member(가입/조회) · auth(로그인/토큰 회전) · product(카탈로그/재고) · order(주문/취소) · cart(장바구니) 5개 도메인.

## 2. 기술 스택 & 버전
| 영역 | 기술 | 버전/비고 |
|---|---|---|
| 언어 | Java | 21 (toolchain) |
| 프레임워크 | Spring Boot | **3.5.14** (4.0 금지 — 의도적 고정) |
| 빌드 | Gradle (Wrapper) | 8.14.5 |
| 보안/인증 | Spring Security + JWT(jjwt) | jjwt 0.12.6, HS256 |
| ORM | Spring Data JPA + Hibernate | `ddl-auto: update`(로컬) |
| DB(운영/로컬) | MySQL 8.0 | Docker, host 3307 → container 3306 |
| DB(테스트) | H2 in-memory | MODE=MySQL, 별도 test yml |
| API 문서 | springdoc-openapi | 2.8.6 (Swagger UI) |
| 기타 | Lombok · Validation · Actuator · JUnit5 | — |

좌표: groupId `com.commerce`, artifactId `commerce-api`, 루트 패키지 `com.commerce.api`.

## 3. 모노레포 구조
```
commerce-api/ (repo root)
├── backend/    Spring Boot 앱 (모든 gradle/docker 명령은 여기서 실행)
├── frontend/   (예정)
├── docs/       architecture.md, dev-log.md
└── .claude/    skills · settings · Stop훅
```

## 4. 패키지 / 계층 구조 (도메인형)
```
com.commerce.api
├── member/   controller·service·repository·entity(Member, Role)·dto
├── auth/     controller·service·repository·entity(RefreshToken)·dto
├── product/  controller·service·repository·entity(Product, ProductStatus)·dto
├── order/    controller·service·repository·entity(Order, OrderItem, OrderStatus)·dto
├── cart/     controller·service·repository·entity(Cart, CartItem)·dto
└── global/   config(JpaConfig, SecurityConfig, OpenApiConfig)
              common(BaseEntity, ApiResponse)
              exception(BusinessException, GlobalExceptionHandler)
              security(JwtTokenProvider, JwtAuthenticationFilter, SecurityUtil)
```
계층 흐름: **Controller → Service → Repository → Entity**, DTO(record)는 경계에서만.

| 계층 | 책임 |
|---|---|
| Controller `@RestController` | 라우팅, `@Valid`, `ApiResponse` 래핑 |
| Service `@Service @Transactional` | 비즈니스 로직, 트랜잭션 경계(기본 readOnly) |
| Repository `JpaRepository` | 데이터 접근, 메서드명 쿼리 |
| Entity | 도메인 모델 + 도메인 로직(애그리거트 루트) |
| DTO(record) | 불변 요청/응답 계약 |

## 5. 요청 처리 흐름

**일반(공개)**
```
Client → JwtAuthenticationFilter(토큰 없음→통과) → SecurityFilterChain(permitAll)
       → Controller(@Valid) → Service(@Transactional) → Repository → DB
       ←─ ApiResponse<T>
```

**JWT 인증 요청**
```
Client (Authorization: Bearer <accessToken>)
 → JwtAuthenticationFilter (OncePerRequestFilter)
     1) "Bearer " 추출  2) validate(HS256)
     3) role claim 있으면(=access) → UsernamePasswordAuthenticationToken
          principal=memberId(Long), authorities="ROLE_"+role
     4) SecurityContext에 저장
 → 인가 검사(SecurityConfig)
 → Controller: SecurityUtil.getCurrentMemberId() (없으면 401)
 → Service → Repository → DB → ApiResponse<T>
```
- 세션 `STATELESS`, CSRF/Basic/formLogin 비활성. 필터는 `UsernamePasswordAuthenticationFilter` **앞**에 등록.
- **role claim 없는 토큰(=refresh)은 SecurityContext에 설정되지 않음 → 요청 인증 불가**(의도된 설계).

## 6. 보안 아키텍처
- **비밀번호**: `BCryptPasswordEncoder`(salt 내장). 가입 시 인코딩, 로그인 시 `matches()`. 응답 DTO에서 password 제외.
- **JWT 서명**: HS256 대칭키. 시크릿은 `application.yml`(로컬) — 운영은 환경변수화 TODO.
- **principal = memberId(Long)**: 위변조 불가, DB 직매핑.
- **역할**: `Role`(USER/ADMIN). Spring Security가 `ROLE_` 접두사 부여(`JwtAuthenticationFilter`에서 `"ROLE_"+role`). 가입은 USER, ADMIN은 직접 설정.

**토큰 설계**
| | Access | Refresh |
|---|---|---|
| 만료 | 30분 | 14일 |
| subject | memberId | memberId |
| role claim | 있음 | **없음** |
| jti(UUID) | 있음 | 있음 |
| 상태 | 무상태 | `refresh_token` 테이블 저장(stateful) |
| 요청 인증 | 가능 | 불가 |

- 응답 형태: `TokenResponse { accessToken, refreshToken, tokenType:"Bearer" }`.
- **jti(UUID)**: 같은 초에 발급돼도 토큰이 달라짐 → 회전·재사용 탐지.
- **회전(rotation)**: refresh 시 저장본과 정확히 일치해야 하고, 새 토큰 발급과 동시에 저장값 in-place 갱신(멤버당 1레코드, `findByMemberId`+`ifPresentOrElse` upsert). 불일치/이미 회전됨 → 401.

**경로 인가 매트릭스**
| 경로 | 인가 |
|---|---|
| `POST /api/auth/**` (login/refresh) | permitAll |
| `POST /api/members` (가입) | permitAll |
| `GET /api/products/**` (조회) | permitAll |
| `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health` | permitAll |
| `POST /api/products` (등록) | **hasRole('ADMIN')** |
| `GET /api/members/{id}`, `/api/orders/**`, `/api/carts/**`, 그 외 | **authenticated** (anyRequest) |

## 7. 데이터 모델
| 엔티티 | 테이블 | 핵심 컬럼 | 관계 |
|---|---|---|---|
| Member | `member` | id, email(unique), password(**nullable** — 소셜 유저), nickname, role(enum), **provider(enum,기본 LOCAL)·providerId**(OAuth2 대비, §12) | 독립 |
| RefreshToken | `refresh_token` | id, memberId(unique), token(varchar512) | memberId → Member (ID참조) |
| Product | `product` | id, name, price(**long** 원), stock(int), description, status(enum) | 독립 |
| Order | `orders`(예약어 회피) | id, memberId(ID참조), status(enum), totalPrice(long) | @OneToMany OrderItem |
| OrderItem | `order_item` | id, order_id(FK), productId(ID참조), productName/orderPrice(**스냅샷**), quantity | order(@ManyToOne) |
| Cart | `cart` | id, memberId(unique) | @OneToMany CartItem |
| CartItem | `cart_item` | id, cart_id(FK), productId(ID참조), quantity | cart(@ManyToOne) |

모든 엔티티 `BaseEntity`(createdAt/updatedAt) 상속. enum은 모두 `@Enumerated(STRING)`.

**관계 원칙 — 애그리거트 경계**
```
Member (1)──ID참조──(1) RefreshToken / Cart      [memberId unique]
Member (1)──ID참조──(N) Order
Order  (1)══객체연관══(N) OrderItem   [@OneToMany, cascade ALL, orphanRemoval]
Cart   (1)══객체연관══(N) CartItem    [@OneToMany, cascade ALL, orphanRemoval]
OrderItem ──ID참조──> Product (+ name/price 스냅샷)
CartItem  ──ID참조──> Product (스냅샷 없음, 조회 시 enrich)

 ══ 애그리거트 내부 객체연관(FK+cascade)   ── 애그리거트 간 ID참조(Long, FK객체 없음)
```

## 8. 도메인별 요약
| 도메인 | 책임 | 핵심 로직 |
|---|---|---|
| **member** | 가입/조회 | `existsByEmail` 중복검사(→409), BCrypt 인코딩, 기본 role USER. 조회 실패 404. |
| **auth** | 로그인/토큰갱신 | 로그인: email + `matches()`(실패 401) → access+refresh. refresh: 저장본 일치 검증 → 회전. 멤버당 단일 refresh(upsert). |
| **product** | 카탈로그/재고/상태 | 신규 ON_SALE. `decreaseStock()`/`increaseStock()`는 **엔티티 메서드**(재고부족 409). 물리삭제 대신 status 전이(소프트삭제). |
| **order** | 주문 애그리거트 | 생성: 가격/이름 **스냅샷** + 재고차감, 원자적(실패 시 전체 롤백). **동시 주문은 `@Version` 낙관적 락으로 초과판매 방지** — `OrderService.create`(@Retryable) → `OrderProcessor.place`(@Transactional) 위임으로 충돌 시 새 트랜잭션 재시도. `cancel()`: 재고 복원, 이미 취소면 409. |
| **cart** | 멤버당 장바구니 | 멤버당 1개(memberId unique). `addItem` 중복 시 수량증가. CartItem **스냅샷 없음** — 조회 시 `findAllById` 배치(N+1 방지)로 enrich, 삭제된 상품은 `"(삭제된 상품)"` placeholder. 빈 카트는 합성 응답. removeItem: 카트 없으면 404, 항목 없으면 조용히 무시. |

## 9. 횡단 관심사 (global)
| 관심사 | 구현 |
|---|---|
| 감사 | `BaseEntity`(@MappedSuperclass) + `JpaConfig`(@EnableJpaAuditing): createdAt(불변)/updatedAt 자동 |
| 공통 응답 | `ApiResponse<T>` {success, message, data}. 성공/검증오류/비즈니스예외/시스템오류 단일 형태. 오류는 `error(message)`(data=null, success=false) |
| 전역 예외 | `GlobalExceptionHandler`(@RestControllerAdvice): BusinessException→보유 HttpStatus / Validation→400 / HttpMessageNotReadable→400 / 그 외→500(로깅) |
| 비즈니스 예외 | `BusinessException` extends RuntimeException + HttpStatus 보유 → 도메인이 상태코드 결정 |
| API 문서 | `OpenApiConfig`(springdoc): `/swagger-ui.html`, `/v3/api-docs` |
| 보안 헬퍼 | `SecurityUtil.getCurrentMemberId()`: principal(memberId) 추출, 없으면 401 |

## 10. 전체 API 엔드포인트
| METHOD | Path | 인가 | 설명 |
|---|---|---|---|
| POST | `/api/auth/login` | permitAll | 인증 → access+refresh (실패 401) |
| POST | `/api/auth/refresh` | permitAll | 회전 재발급 (불일치 401) |
| POST | `/api/members` | permitAll | 가입 201 (중복 409) |
| GET | `/api/members/{id}` | authenticated | 조회 200 (없으면 404) |
| GET | `/api/products/{id}` | permitAll | 조회 200 (없으면 404) |
| POST | `/api/products` | ADMIN | 등록 201 |
| POST | `/api/orders` | authenticated | 주문(스냅샷+재고차감) 201 |
| GET | `/api/orders/{id}` | authenticated | 조회 200 (없으면 404) |
| POST | `/api/orders/{id}/cancel` | authenticated | 취소(재고복원) 200 (이미취소 409) |
| POST | `/api/carts/items` | authenticated | 담기(없으면 생성, 중복 수량증가) |
| GET | `/api/carts` | authenticated | 내 장바구니(상품 enrich) |
| DELETE | `/api/carts/items/{productId}` | authenticated | 항목 제거 (카트 없으면 404) |

## 11. 핵심 설계 결정
1. **스냅샷 vs 라이브 참조**: Order/OrderItem은 가격·이름 스냅샷(이력 보존), Cart는 스냅샷 없이 조회 시점 최신 정보 enrich.
2. **애그리거트 간 ID 참조**: memberId/productId는 항상 `Long`(객체 연관 금지) → 결합도↓·경계 명확·불필요 로딩 회피.
3. **돈은 long**(원 단위 정수) → 부동소수점 오차 방지.
4. **enum은 `@Enumerated(STRING)`** → ordinal 대비 안전.
5. **JWT stateless** + access/refresh 분리 + 회전 + jti.
6. **principal = memberId(Long)** + SecurityUtil null 시 401.
7. **HS256**(단일 서비스 적합; 다중 서비스면 RS256).
8. **도메인 로직은 엔티티에**(재고 변동/주문 합계·취소/장바구니 dedup).
9. **생성 제어**: `@NoArgsConstructor(PROTECTED)` + Builder/팩토리(`Order.create`).
10. **DTO는 record**(불변, 엔티티 누출 방지, `from()` 매핑 중앙화).
11. **트랜잭션 기본 readOnly**, 쓰기 메서드만 `@Transactional`.
12. **소프트 삭제**(상품 status 전이) → 주문 참조 무결성 보존.
13. **N+1 방지**: Cart 조회 시 `findAllById` 배치.
14. **테스트는 H2**(MySQL 비의존, 격리), 운영/로컬은 MySQL(Docker).
15. **재고 동시성 = 낙관적 락(@Version) + 재시도**: 동시 차감 충돌은 커밋 시 감지(초과판매 방지), `@Retryable`로 새 트랜잭션 재시도(재시도는 트랜잭션 워커 빈 분리로 보장). 동시성 테스트로 검증.

## 12. 인증 확장: OAuth2 / 소셜 로그인 (설계)

> 로컬(email/password)에 더해 구글·카카오·네이버 등 **소셜 로그인**을 받기 위한 설계. **소셜 로그인 구현 자체는 후속**이며, 유저 모델은 Flyway **V2 마이그레이션으로 미리 대비**(provider/providerId/nullable password)했다.

### 12.1 핵심 원칙 — "검증 주체만 갈아끼우고, 토큰 발급은 그대로"
소셜 로그인 = 비밀번호 확인 대신 **외부 제공자(IdP)가 신원을 확인**하는 것. 인증 성공 *이후*에는 로컬과 **동일하게 우리 JWT(httpOnly access/refresh 쿠키)를 발급**한다 → 기존 인증/인가 파이프라인(`JwtAuthenticationFilter`·쿠키·401/403·refresh 회전) 전부 재사용. (.NET의 외부 인증 핸들러 + 자체 토큰 발급과 동형.)

### 12.2 유저 모델 — 단일 테이블
- `Member.provider`(LOCAL/GOOGLE/KAKAO/NAVER) + `Member.providerId`(제공자 고유 ID = 소셜의 `sub`). LOCAL은 password 보유, 소셜은 **password null**.
- **식별자 = (provider, providerId)**. ⚠️ email은 제공자 측에서 바뀔 수 있어 1차 식별자로 부적합 — 연동 보조 키로만.
- **단일 테이블** 채택(표준·간단). "한 사람이 로컬+구글+카카오를 *한 계정*에 연동"이 요구사항이 되면 분리 테이블(`member` 1—N `social_account`)로 확장.

### 12.3 로그인 플로우 (구현 시)
```
브라우저 → /oauth2/authorization/{google} → IdP 로그인·동의
 → 콜백 → Spring Security OAuth2 Client → 커스텀 OAuth2UserService
     1) provider + sub + email + name 추출
     2) Member find-or-create  (provider, providerId 기준)
     3) 우리 JWT(access·refresh) 발급 → httpOnly 쿠키 Set-Cookie
 → 프론트로 리다이렉트 (이후 요청은 기존 쿠키 인증과 동일)
```
- 의존성: `spring-boot-starter-oauth2-client`. 제공자 **client-id/secret은 환경변수**(시크릿 분리 원칙과 동일).

### 12.4 계정 연동 & email 유니크 — 보류(구현 시 확정)
같은 이메일을 로컬·구글 둘 다 쓰는 경우의 정책:
- **(A) 이메일 자동 연동**: 같은 *검증된* 이메일 = 한 계정. UX 좋음. ⚠️ 제공자가 이메일을 검증해야 안전(미검증 이메일 신뢰 시 계정 탈취 위험; 구글·카카오는 검증).
- **(B) provider별 별개 계정**: 단순·안전, 단 중복 계정 발생.
- 현재는 email unique 유지. 정책 확정 시 유니크 제약을 (provider, providerId) 또는 (provider, email) 기준으로 재설계.

### 12.5 현재 prep 상태 (V2 완료)
- `V2__add_oauth_fields.sql`: `password` nullable, `provider`(enum, 기본 LOCAL)·`provider_id`(nullable) 추가. 기존 회원은 LOCAL 부여, 로컬 로그인 영향 없음.
- ⚠️ Hibernate(Boot 3.5)는 `@Enumerated(STRING)`을 **MySQL 네이티브 ENUM**으로 매핑 → 손수 쓰는 마이그레이션의 enum 값 집합을 **알파벳순**으로 두어 `validate` 기대값과 일치시킴.

---

## 13. 결제(Payment) 설계

> 결제 도메인. **모의 PG**(외부 연동 없이 시뮬레이션)로 시작하되 **포트-어댑터로 추상화**해 실제 PG(토스/포트원) 교체가 가능하게 한다. 재고는 **결제 승인 시점에 차감**(주문 생성 시 X). 구현은 단계적(P1 골격 → P2 흐름전환 → P3 결제API/멱등성 → P4 취소·환불).

### 13.1 포트-어댑터 (외부 연동 추상화)
- `PaymentGateway`(인터페이스=포트): `approve(거래정보) → 결과(pgTransactionId, 성공여부)`.
- `MockPaymentGateway`(어댑터): 외부 호출 없이 가짜 `pgTransactionId` 발급 + 승인/실패 결정(테스트용 실패 트리거). 운영 전환 시 **같은 인터페이스에 실제 PG 어댑터만 교체**(DIP). (.NET `IPaymentGateway` + DI와 동형.)

### 13.2 상태머신 & 주문↔결제 흐름
- `OrderStatus`: `PENDING`(결제대기) → `PAID`(결제완료) / `CANCELLED`. **기존 `ORDERED` 제거** — 결제 도입으로 "주문=완료"가 깨짐.
- `PaymentStatus`: `READY` → `PAID` / `FAILED`, `PAID` → `CANCELLED`(환불).
```
체크아웃 → 주문 PENDING (스냅샷만, 재고 차감 ❌)
결제 요청 → MockGateway.approve
   성공 → [한 트랜잭션] 재고 차감(@Version+재시도) + Order PAID + Payment PAID
   실패 → Payment FAILED, Order PENDING 유지(재결제 가능)
주문 취소 → (PAID였으면) 재고 복원 + Order/Payment CANCELLED
```
- **핵심 이동**: 재고 차감이 `OrderProcessor.placeOrder` → **결제 승인 단계**로 이동. 낙관락 재시도(@Retryable + 새 트랜잭션)도 결제 승인 워커로 따라감.

### 13.3 멱등성 (중복 결제 방지)
- 클라이언트가 `idempotencyKey`(UUID)를 결제 요청에 포함 → `Payment.idempotencyKey` **unique**. 동일 키 재요청은 새 결제 대신 **기존 결과 반환**(네트워크 재시도·더블클릭 방어).

### 13.4 데이터 모델 (Flyway V3)
- `Payment`: id · `orderId`(Long, ID참조) · amount · `status`(PaymentStatus) · method · pgTransactionId · `idempotencyKey`(unique) · BaseEntity.
- 주문↔결제 = 다른 애그리거트 → **ID 참조**(객체연관 X, §11 준수).
- `V3__add_payment.sql`: payment 테이블 + `orders.status` enum 값 변경(ORDERED → PENDING/PAID).

### 13.5 확장 지점 (지금은 미도입 — 이음새만 확보)
테스트 단계라 단순(MySQL+JPA)하게 가되, 경계만 끊어두어 추후 무중단 확장:
- **Redis**: 멱등키 저장(TTL), 재고 분산락(스케일아웃 시 `@Version` 보완), 결제세션 캐시.
- **RabbitMQ/Kafka**: 결제완료 **이벤트 발행**(주문확정·배송·알림 디커플링), **아웃박스 패턴**(DB 커밋↔메시지 발행 원자성), 실제 PG **웹훅 비동기 수신**.
- **실제 PG**: `PaymentGateway`에 토스/포트원 어댑터 추가 + 결제창(프론트)·웹훅.

---

> **알려진 TODO/한계**: ① ~~재고 oversell~~ → **@Version+재시도로 해결(완료)**. ② ~~JWT 시크릿 환경변수화~~ → **OS 환경변수(12-factor)·`.env`로 분리(완료)**. ③ ~~`ddl-auto: update` → Flyway~~ → **Flyway 도입·`validate` 전환(완료)**. ④ 장바구니 수량/재고 검증 없음(담기 단계, 의도적 — 주문 시 검증). ⑤ ~~frontend 미구현~~ → **Next.js 구매흐름 구현 중**. ⑥ ~~FE CORS~~ → **완료**. ⑦ OAuth2/소셜 로그인 — 유저 모델 prep 완료(§12), 구현은 후속.
>
> ⚠️ **문서 갱신 지연 주의**: §5~§10 일부는 초기(5도메인·헤더 JWT·ddl update) 기준이라 현행과 차이가 있음 — 실제 현행은 category·brand 도메인 추가, 상품 옵션(사이즈) 도입, **인증이 httpOnly 쿠키 기반**, Flyway 적용 등. 정확한 최신 이력은 `dev-log.md` 참고.
