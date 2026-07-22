# 백로그 (자율 진행 큐)

> CLAUDE.md "자율 진행 모드"가 이 파일의 **READY**를 위에서부터 막힐 때까지 처리한다.
> 각 READY = 결정이 끝나 바로 구현 가능한 1 step(외부 프로그램 연동 없음). 완료 시 DONE으로 옮기고 dev-log 기록.
> 외부 프로그램 연동(RabbitMQ·외부 API·새 외부 도구)은 자율 금지 → "함께(학습)"에서 사용자와 직접.

## READY (결정 완료 · 외부 무관 · 자율 진행 가능 · 위에서부터)
> 기능 9건·커버리지 3건 소진 후 **2026-07-21 무결정 스캔**(4앵글, owner 판단 필요 항목 전부 배제·소스 재검증)에서 8건 재충전. **#1~#4는 동일 뿌리(order-item CANCELLED 상태 미존중)의 실제 머니 버그** — 인접 코드라 순차 실행 시 회귀테스트 상호 보강. **#1~#4 완료(07-22)** → DONE.

- **#6 [robustness] 짧은 varchar @Size 누락 → 500 대신 400** (S·BE·마이그0) — 브랜드/카테고리 name·옵션 size가 `@NotBlank`만 → 길이초과가 DB위반 500. 형제 DTO는 다 `@Size` 병행. → `@Size(max=컬럼길이)`.
- **#7 [parity] 정산 목록/요약에 provider(PG) 필터** (S·both·마이그0) — 대사는 provider 필터 있는데 정산은 없음(컬럼·응답·PG컬럼은 이미 존재). → `SettlementSearchCondition`에 provider 추가.
- **#8 [parity] 감사로그 검색에 targetId 필터** (S·BE·마이그0) — action/targetType/result는 필터하는데 targetId 없음("ORDER 42 전체 이력" 불가). → `eqTargetType` 미러.

### (여유 시·무결정이나 저긴급) 위 8건 소진 후
- 대사 `reconcile`이 findAll 후 Java 필터 → `findBySettledDateBetween`(+인덱스). / 체크아웃·결제 항목당 findByOptionId N+1 → `findByOptionIdIn` 배치. / 순수 테스트 추가(JwtAuthenticationFilter·getProductsForAdmin 회귀·소셜로그인 find-or-create).

## 함께 (외부 연동 · 학습 — 자율 금지)
- 🚧 **배포 ($0 라이브 데모) — 경로 A(Oracle VM) 확정** — 준비물 완료: env화·`Dockerfile`·`docs/deploy.md`(`feature/deploy-prep`) + **prod 산출물·배선 보강**(`feature/deploy-prep-hardening`→dev `06e7ff8`): `backend/docker-compose.prod.yml`(앱+MySQL)·`.env.prod.example`·datasource `${SPRING_DATASOURCE_USERNAME:${MYSQL_USER}}` 체인·`APP_OAUTH2_REDIRECT` 행·`.gitignore` `.env.prod` 차단. 로컬 검증=`bootJar`·`next build` PASS. **결정=경로 A**(Oracle Always Free VM: Vercel FE + VM에 Spring Boot+MySQL; 콜드스타트 없음·진짜 MySQL·쿠키 first-party). ⚠️경로 B는 Koyeb 무료 폐쇄(Mistral 인수)로 약화. **다음=사용자 계정 단계**: Oracle A1 VM 생성→레포 clone→`.env.prod`→`docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build`→Caddy HTTPS→Vercel FE→쿠키전략(Vercel rewrites `/api/*` 프록시=first-party 추천)→로그인 확인. MySQL 실런타임 검증=VM 첫 기동. **VM 준비물 `deploy/`**(vm-setup.sh·Caddyfile·README) + 프록시 뒤 OAuth2 헤더(`server.forward-headers-strategy`) 추가(`feature/deploy-vm-runbook`→dev `2832ed5`). (가이드=docs/deploy.md §3, deploy/README.md)
- ✅ 아웃박스 P2b 실제 RabbitMQ (메시지 브로커) — `feature/outbox-rabbitmq`→dev (377 tests·**런타임 PASS**). 병행 opt-in(`outbox.publisher=in-process|rabbit`)·EventPublisher 포트 어댑터·@RabbitListener 소비·docker-compose rabbitmq(15672). 후속: Testcontainers 실브로커 통합·DLQ.
- 우편번호 검색 API (Daum/Kakao 외부 API)
- ✅ CI (GitHub Actions, `.github/workflows/ci.yml`) — 도입 완료. **다음=스케줄 무인 운영(이 위에)**. 후속: Testcontainers 실DB 통합·브랜치 보호 규칙.

## 결정 필요 (외부 무관이나 결정 미정 — 정하면 READY로)
> 2026-07-20 기능 스캔에서 나온 것들. **자율 진행 금지**(범위·정책·UX 결정) — 사용자가 고르면 READY로 내려온다.

1. **멀티셀러 주문의 상태 단위** ⭐가장 아키텍처적 — 셀러 출고 기능들의 선행 결정. 한 주문에 여러 셀러 상품이 섞이는데 `advanceShipping`은 **Order 전체** status를 움직인다. → (a) 현행 유지·셀러는 조회만(ADMIN이 출고) / (b) 단일셀러 주문에만 셀러 전이 권한 / (c) 상태를 **item·shipment 단위로 내림**(정답에 가깝지만 정산·환불·대사까지 파급).
2. **재고 예약 — 오버셀 구간 제거 여부** — PENDING이 재고를 **전혀 안 잡아** 결제 지연만큼 레이스 구간이 열려 있다. → (a) 현행(결제 시 차감) / (b) 주문 생성 시 차감+취소 복원 / (c) `stock_reservation` TTL 예약(READY ③ 스케줄러 재사용). **동시성 서사로는 (c)가 최고, 공수도 최고.**
3. **반품/교환** — DELIVERED가 막다른 길. 반품창 기간·반품 배송비 부담자·교환=취소후재주문 vs 진짜 스왑·자동승인 vs 셀러승인, 4개가 정해져야 상태머신이 그려진다.
4. **배송비** (`shippingFee` 레포 전체 0회) — `payable = totalPrice - discount`라 PG 금액에 배송비가 **구조적으로 없다**. 나중에 넣으면 결제·환불·정산·대사를 동시에 건드린다. → (a) 도입 안 함(명시) / (b) 정액+무료임계 / (c) 셀러별. + 부분취소 시 환불 여부.
5. **상품 할인가(정가 vs 판매가)** — `Product.price` 단일 필드라 "30% OFF"·SALE 탭·할인율 정렬이 **모델상 불가능**. → (a) `originalPrice` 필드 하나(S) / (b) 기간형 `product_promotion` 테이블(M~L).
6. **알림 인박스(벨) + 재입고 알림** — 아웃박스→RabbitMQ→핸들러는 **완성돼 있는데** `NotificationLog`에 `memberId`도 `readAt`도 없어 **아무도 못 읽는다**(컨트롤러 부재). → 알림 대상 이벤트·수신자 스코프·읽음처리 방식.
7. **게스트 장바구니** — 담기 클릭 = 즉시 `/login`(전환 킬러). → (a) 현행 / (b) localStorage(S·FE) / (c) 서버 토큰 카트+로그인 시 병합(L·BE).
8. **취소·환불 사유 taxonomy** — `Order.cancel()`이 **인자 0개**라 돈이 나가는데 "왜"가 안 남는다. 사유 enum + **정산 귀책·배송비 부담에 영향 주는지**. 정하면 READY ④에 컬럼 하나로 붙는다.

> 그 외 정책 대기: 회원 탈퇴(보존기간·PII 마스킹)·적립금/등급·상품 일괄작업 부분실패 정책·셀러 자가수정 범위·`next/image` 도입(이미지 호스팅처).

## DONE (완료 — 기록)
- [x] (07-22) **#5 돈 흐름 핫패스 인덱스(V40) + stale 주석** — `feature/money-path-indexes`→dev `6e7ec64` (548 유지·**V40**). `settlement_entry.payment_id`(V24서 UNIQUE 제거 후 인덱스 전무)·`payment.order_id`(처음부터 무인덱스)에 조회용 인덱스. `SettlementRepository.existsByPaymentId` 허위 Javadoc(payment_id UNIQUE) 정정. ⚠️**MySQL EXPLAIN 스모크=복귀 후**(H2 Flyway 미적용).
- [x] (07-22) **머니 버그 #4 — 만료 배치 쿠폰 복원** — `feature/order-expiry-coupon-release`→dev `ae8fe56` (547→**548 tests**·마이그0). `OrderExpiryService`가 PENDING 만료 취소 시 발급형 쿠폰을 `release`(수동취소와 대칭) → 결제도 안 했는데 쿠폰만 소멸되던 비대칭 해소. no-op 필터(코드없음/공개형/미보유)는 release가 자체 처리. #1~#4로 항목취소 상태 일관성 뿌리 완결.
- [x] (07-22) **머니 버그 #1~#3 — 항목 CANCELLED 상태 일관성** — `feature/order-cancel-money-fixes`→dev `cd87cfe` (542→**547 tests**·마이그0). 부분취소 후 남는 항목 CANCELLED를 일부 경로가 무시하던 3건: **과다환불**(`cancelOrder`가 잔여=amount−refundedAmount만 환불·`getPayableAmount`=활성 실효가 합) · **400 롤백**(실효가 0 라인은 PG환불·partialRefund(0) 스킵) · **재고 불일치**(`pay()`차감·`cancel()`복원 모두 `isActive()` 가드 → 이중복원·불필요차감 방지). 회귀 5건(Payment 3·OrderService 1·OrderProcessor 1). #4는 같은 뿌리 잔여.
- [x] (07-20~21) **기능 스캔 READY 9건 전부** (508→**518 tests**·FE 0·⚠️V38·V39 MySQL 스모크=복귀 후):
  - ① 상품 검색 브랜드명·설명 확장(서브쿼리) `d93b6ab` · ② 체크아웃 멱등키(V38·IDOR 가드·경쟁조건) `4d89ad8` · ③ PENDING 만료 배치(TTL 설정값·테스트 토글) `72f496d` · ④ 주문 상태 이력+송장(V39·엔티티 소유 타임라인) `2543ed9` · ⑤ 어드민 주문 검색(OrderRepositoryCustom·셀러 스코프 토대) `39e28c4` · ⑥ 셀러 내 주문(searchSellerOrders 재사용·스코프 강제) `dcacb8b` · ⑦ 쿠폰 중단+발급/사용 현황(usedCount 배치집계) `51afc52` · ⑧ FE 기반 3종(Toast·returnTo 가드·error/not-found·soft-404) `92eb6ae` · ⑨ PLP 필터 URL화(자기모순 해소·공유·뒤로가기) `ddf8fc8`.
- [x] (07-14) **커버리지 0% 구멍 3종 메우기** — `feature/coverage-holes`→dev `00d4fa5` (496 tests·+19·프로덕션 코드 변경 0). `AuditLogRepositoryImpl` 0→**98.7%**(감사 검색 QueryDSL이 한 번도 실행된 적 없었음) · `SettlementController` 0→**100%** · `ReconciliationController` 0→**93.3%**(서비스는 91.9%인데 돈 움직이는 HTTP 경계가 0%였음) · `MemberCouponClaimService` 0→**100%**(락 키가 쿠폰별인지 — 전역 키면 처리량 붕괴). 전체 82.6%→**84.5%**.
- [x] (07-14) **SSR 후속 3종** — `feature/ssr-followups`→dev `4a34f26` (FE 0·build 0). `loading.tsx`(`/products`·`/products/[id]` 서버 fetch 대기 = 빈 화면이었음) · **`metadataBase`**(OG 상대경로 경고 소멸) · **필터 URL 색인 정책**=필터 걸린 목록 **noindex,follow**(조합 폭발 → 중복 색인 방지, 상품 링크는 계속 따라감)·**canonical은 자기참조 유지**(깨끗한 URL로 정규화하면 noindex와 모순 신호)·`page`는 필터가 아니라 색인 유지.
- [x] (07-14) **JaCoCo 커버리지 리포트** — `feature/jacoco-coverage`→dev `a93ad54`. `test finalizedBy jacocoTestReport`(한 번에 리포트)·Q클래스/DTO/config 제외·CI 아티팩트 업로드·`docs/coverage.md`. **기준선 instruction 82.6%·branch 70.1%**. 🔴발견=`AuditLogRepositoryImpl`·`MemberCouponClaimService`·정산/대사/쿠폰 컨트롤러 **0%** / 🟢`SettlementService` 91.9%. → 구멍 3건을 READY로 재충전.
- [x] (07-14) **리뷰 정렬·필터·평점 분포** — `feature/review-sort-distribution`→dev `9a0ea6a` (477 tests·FE 0·마이그0). `?rating=&photoOnly=&sort=`(QueryDSL 동적 where + Pageable 정렬·id desc tie-breaker) + `/reviews/summary`(group by rating → **5★~1★ zero-fill**·평균은 **분포에서 계산**해 단일 출처). FE=**분포 막대가 곧 필터**(누르면 그 별점만)·사진리뷰 토글·정렬 드롭다운.
- [x] (07-14) **@RateLimit AOP 일반화 + 429 Retry-After** — `feature/ratelimit-aop`→dev `326a43f` (469 tests·마이그0). `@RateLimit(key, limit, by=SpEL)`+`RateLimitAspect`(@Auditable 패턴)로 3곳의 손조립 키 흡수(로그인 이메일 5/분·claim 회원 20/분·피드 IP 60/분)·컨텍스트 없으면 `unknown`(제한 무력화 방지)·**Retry-After 60**(`RateLimitExceededException`+전용 핸들러). ProductController에서 `HttpServletRequest` 제거. ⚠️@WebMvcTest엔 AOP 미로딩 → 검증은 아스펙트 단위 + @SpringBootTest 통합.
- [x] (07-14) **감사로그 CSV 내보내기 + 드릴다운** — `feature/audit-csv-drilldown`→dev `30c326b` (466 tests·FE 0·마이그0). `GET /api/audit-logs/export`(ADMIN·같은 필터): StreamingResponseBody+1000행 청크·**스냅샷 경계**(to 미지정 시 시작 시각 고정 → 페이지 밀림/행 중복 방지)·**UTF-8 BOM**(엑셀 한글)·RFC 4180 이스케이프·상한 5만+절단 안내·**AUDIT_EXPORT 자체 감사**. FE=CSV 버튼·`apiDownload`(fetch+Blob)·행 클릭 상세 모달.
- [x] (07-14) **재고 임박·품절 리포트** — `feature/low-stock-report`→dev `1dfc7f8` (457 tests·FE 0·마이그0). `GET /api/dashboard/low-stock`(ADMIN·threshold/limit 클램프): **옵션(SKU) 단위**(QueryDSL option→product 조인·재고 오름차순)·품절/임박 전체 카운트·판매중지 제외·**비캐시**(재고 신선도가 곧 기능). FE `/admin` 위젯(뱃지 카운트·기준칩 ≤3/5/10·상위 10건). `/api/dashboard/**` ADMIN 매처 재사용 → SecurityConfig 0.
- [x] (07-14) **어드민 회원 관리** — `feature/admin-members`→dev `d15e28f` (452 tests·FE 0·마이그0). `GET /api/members/admin`(ADMIN·QueryDSL 키워드[이메일 OR 닉네임]·role·가입 최신순) + `PATCH /api/members/{id}/role`(@Auditable MEMBER_ROLE_UPDATE) + FE `/admin/members`. **가드**=자기자신 409(관리자 락아웃 방지)·SELLER 지정 400(sellerId 연결 필요)·SELLER 강등 시 sellerId 해제. 파생=게이트 스켈레톤 NAV 자동화·감사 필터 targetType 11종 복구.
- [x] (07-14) **최근 본 상품** — `feature/recently-viewed`→dev `d147793` (439 tests·FE 0·마이그0). `GET /api/activity/recently-viewed`(로그인·limit·exclude): append-only 로그를 **`group by product_id` + `order by max(created_at) desc`**로 상품별 1건(마지막 조회순), 판매중지·삭제 상품 제외(후보 3배 조회 후 컷), 폴백 없음. FE `RecentlyViewedSection`(홈·상세[현재 상품 exclude]) + **공용 `ProductRail` 추출**(추천·함께산상품 카드 복붙 해소). 결정=**activity 도메인에 배치**(product→activity 역방향 의존·공개 매처 순서 함정 회피). ⚠️MySQL 스모크=Docker 복귀 후.
- [x] (07-12) **배포 전 필수 3종** — `feature/pre-deploy-hardening`→dev `a6962cb` (433 tests·FE 0·🟢런타임 PASS). ①**판매중지 데이터잠금 실버그**(어드민이 공개 API 재사용 → DISCONTINUED 복귀 불가): `GET /api/products/admin`(전 상태·status 필터)+FE 필터칩, ⚠️매처 순서(공개 GET 앞) ②**Actuator 공개 노출 차단**: `MANAGEMENT_ENDPOINTS` env(운영=health)+Caddy 404, 파생=rabbit/redis 헬스로 `/actuator/health` 503→**200 UP**(UptimeRobot 오탐 해결) ③**돈흐름 감사** @Auditable 10곳(정산·payout·대사·환불). 파생=`NoResourceFoundException`→404(catch-all 500·5xx 알림 오탐 제거).
- [x] (07-07) **어드민 감사 로그 (AOP)** — `feature/admin-audit-log`→dev `01b8e86` (431 tests·FE 0). 새 `audit` 도메인: `@Auditable`+AuditAspect(@Around, 성공/실패 자동기록·SpEL 대상ID·REQUIRES_NEW·best-effort)·`GET /api/audit-logs`(ADMIN·QueryDSL 필터·행위자 enrich)·**V37**·6도메인 23개 어드민 변경 부착·FE `/admin/audit`. **🟢 V37 MySQL 런타임 스모크 PASS**(07-07: Flyway v37·validate 통과 / CATEGORY_CREATE SUCCESS·CATEGORY_UPDATE FAILURE 적재·SpEL 대상ID·REQUIRES_NEW 증명). 후속=상세 드릴다운·CSV 내보내기·failure 감사 알림.
- [x] (06-29) **파라미터 타입 불일치 400** — `def391d`. 비숫자 PathVariable 500→400(MethodArgumentTypeMismatch 핸들러). 411 tests.
- [x] (06-29) **DB 조회 인덱스 V36** — `53c3f44`. orders(member_id)·review(product_id)·order_item(product_id)·settlement_entry(seller_id·payout_id). 저카디널리티/복합UNIQUE-prefix 제외. **Flyway 부팅 적용(v36)+EXPLAIN key 선택 검증**.
- [x] (06-29) **FE 상품목록 무한스크롤** — `6b353df`. 기본뷰=커서 `/feed`, 필터/정렬=offset. IntersectionObserver·generation 레이스가드. tsc/lint 0·라이브 200 검증.
- [x] 옵션 API(추가/수정/삭제) + 어드민 옵션 UI — `94b…`/`6a43281` (정적+MySQL 런타임 PASS)
- [x] 상품 상태 변경 API (`PATCH .../status`) — `1f14521` (정적+런타임 PASS)
- [x] 대표 이미지 갤러리 (ProductImage·**V32**) — `7029973` (정적+MySQL 스모크 PASS)
- [x] 상품 수정 API (`PUT /api/products/{id}`) — `94298a5` (332 tests)
- [x] 어드민 상품 상태·이미지 관리 UI + apiPatch — `6bd386d` (FE 0)
- [x] 어드민 상품 등록·수정 폼 — `fa7d30d` (FE 0)
- [x] 카테고리 2단계 계층화 (`category.parent_id`·**V33**) — `3a1bc10` (335 tests). ⚠️V33 MySQL 스모크 복귀 후
- [x] CI (GitHub Actions) — `c55d728` (첫 런 초록불 확인)
- [x] 어드민 카테고리 관리 화면 (`/admin/categories`) — `b84a040` (tsc/lint 0, BE 무변경)
- [x] 어드민 브랜드 관리 화면 (`/admin/brands`) — `b84a040` (tsc/lint 0, BE 무변경)
- [x] 추천 배치 멱등(중복키) 버그 수정 — `df61e19` (deleteByMemberId 벌크 DELETE화; dev 서버 기동 복구)
- [x] 카테고리·브랜드 수정/삭제 API (PUT/DELETE) — `feature/category-brand-update-delete`→dev (359 tests·마이그0). 삭제는 캐스케이드 없이 409 차단(카테고리 자식·상품 참조 / 브랜드 상품 참조)·카테고리 수정은 이름+부모 재배치(2단계 가드)·ADMIN 매처 추가
- [x] 어드민 카테고리/브랜드 수정·삭제 UI 연결 (FE) — `feature/admin-category-brand-edit-delete`→dev (tsc 0·lint 0·BE 359 유지). 인라인 편집+`confirm()` 삭제(상품옵션 어드민 패턴), 카테고리 부모 재배치 select·브랜드 이름 인라인. 409 메시지 노출
- [x] 주문 배송 상태 (PAID→SHIPPING→DELIVERED, forward-only·**V34**) — `feature/order-shipping-status`→dev (370 tests·tsc/lint 0). `Order.advanceShipping` 전이가드(건너뛰기/되돌리기 409)·`PATCH /api/orders/{id}/status`·`GET /api/orders/admin`·FE `/admin/orders`. 파생: 배송후 취소 차단·구매기준 `OrderStatus.PURCHASED`로 확장(리뷰/추천). ⚠️V34 MySQL 스모크 사용자 복귀 후
- [x] PLP 카테고리 필터 2단계 표시 (FE) — `feature/plp-category-2level`→dev (tsc/lint 0·BE 370 유지). `/products` 카테고리 드롭다운 부모→자식 `└ ` 들여쓰기(`categoryFilterOptions`)·어드민 폼 컨벤션·Select 무변경
- [x] 대사 일자별 윈도우 (선택적 from/to) — `feature/reconciliation-daily-window`→dev (373 tests·마이그0). 윈도우 기준=정산일(우리 settledDate·PG 신규 settledOn)·`reconcile(from,to)`(무인자=전체)·OPEN 삭제 윈도우 키로 스코프·`POST /run?from=&to=`. PG 게이트웨이 계약(PgSettlementRecord.settledOn) 변경 수용
- [x] 선착순 한정수량 쿠폰 (동시성 제어·**V35**) — `feature/coupon-claim-concurrency`→dev (384 tests). 원자적 조건부 UPDATE(`incrementIssuedCount` 한도 내만 +1·DB 행 락)+member_coupon UNIQUE+트랜잭션 롤백. `POST /api/member-coupons/claim/{id}`·**동시성 통합테스트(30명 동시→정확히 10장·초과 0)**. 후속=잔여수량 노출·FE 버튼·Redis 분산락
- [x] 선착순 쿠폰 후속: 받을 수 있는 쿠폰 목록 + 받기 버튼(FE) — `feature/coupon-claimable-list`→dev (385 tests·tsc/lint 0). `CouponResponse` 잔여수량 노출 + `ClaimableCouponResponse`(remaining·soldOut·alreadyClaimed) + `GET /api/member-coupons/claimable`(ISSUED+ACTIVE+기간 내) + `/account/coupons` 2섹션(받기 버튼·이미받음/마감 비활성). 후속=브라우저/MySQL 스모크(사용자 복귀 후)·Redis 분산락
- [x] 어드민 대시보드 (`/admin` 랜딩) — `feature/admin-dashboard`→dev (388 tests·tsc/lint/build 0). 새 `dashboard` read-model 도메인 + `GET /api/dashboard?days=`(ADMIN): KPI(주문/매출/정산대기/회원/판매중상품)·주문 상태별 분포·일별 매출 추이(자바 그룹핑 zero-fill). FE `/admin/page.tsx`(KPI 카드·분포·recharts AreaChart 7/30일)·NAV "대시보드"·로그인 ADMIN→`/admin`. recharts@3 추가. 후속=브라우저/MySQL 스모크(사용자 복귀 후)
- [x] 캐싱 (Caffeine, 외부 0) — `feature/caching-caffeine`→dev (392 tests). Spring Cache 추상화+Caffeine(`CacheConfig`·per-cache TTL·`app.cache.enabled` 토글로 테스트 기본 OFF=NoOp). 상품 상세 `@Cacheable`+무효화(상품 수정/상태/옵션/이미지 + 교차도메인 리뷰·찜) / 카테고리·브랜드 목록 `@Cacheable`+변경 시 evict. `CacheTest`(적중·무효화). 후속=Redis 교체·대시보드/인기상품 캐시·적중률 메트릭
- [x] 캐시 적중률 메트릭 — `feature/cache-metrics`→dev (393 tests·외부 0). Caffeine `.recordStats()` + Actuator `metrics`/`caches` 노출 + 새 `monitoring` 도메인 `GET /api/monitoring/caches`(ADMIN: 캐시별 hit/miss/적중률/축출/크기). 후속=Prometheus/Grafana·대시보드 적중률 카드·Redis 교체
- [x] Redis 분산 캐시 (병행 opt-in) — `feature/redis-cache`→dev (393 tests·**런타임 PASS**). `app.cache.provider=caffeine|redis` 토글(`@ConditionalOnExpression`)로 서비스 코드 0줄 변경하고 CacheManager만 Caffeine↔Redis. `GenericJackson2JsonRedisSerializer`(JavaTimeModule·DefaultTyping.EVERYTHING로 레코드/List 역직렬화). docker-compose redis(profile opt-in). 런타임=provider=redis로 세 캐시 적재·역직렬화 검증. 후속=Redis 분산락·Redis 모드 적중률 노출
- [x] Redis 분산락 (선착순 쿠폰, DIY SETNX+Lua) — `feature/coupon-redis-lock`→dev (395 tests·**런타임 PASS**). 먼저 방식 비교 분석(`docs/distributed-lock-study.md`)→② DIY 선택. 포트-어댑터 `DistributedLock`(NoOp 기본/Redis opt-in `app.lock.provider`). Redis=SET NX PX + Lua 안전해제, `MemberCouponClaimService`가 claim tx를 락으로 감쌈(DB 원자 UPDATE는 정합성 백스톱 유지·`MemberCouponService.claim` 무변경). `RedisDistributedLockTest`(상호배제, Redis 없으면 skip). 후속=Redisson watchdog 비교·부하테스트
- [x] Redisson watchdog 비교 실습 — `feature/redisson-lock-comparison`→dev (397 tests·**런타임 PASS**). 같은 `DistributedLock` 포트에 `RedissonDistributedLock`(redisson core 4.6.1·`app.lock.provider=redisson`·watchdog tryLock) 추가. `LockComparisonTest` 실측: 작업 2s>lease 1s에서 **DIY 동시=2(만료로 깨짐)** vs **Redisson watchdog 동시=1(자동 연장 유지)**. study 문서 §5 표. 후속=부하테스트(k6)로 처리량 수치화
- [x] 부하 테스트 ① 캐시 처리량 (k6) — `feature/load-test-cache`→dev. `grafana/k6` 도커(무설치)·`backend/load-test/cache-throughput.js`. GET 상품상세 20VU·20s, 캐시 ON vs OFF: **RPS 3,148 vs 591(~5.3×)·p95 3.26ms vs 43.26ms(~13×)**. 교훈=50VU서 Docker NAT 포화로 측정 오염→부하 낮춰 앱 측정. `load-test/README.md` 기록. 후속=② 선착순 쿠폰 정합성+처리량(락 모드 대조)
- [x] 부하 테스트 ② 선착순 쿠폰 정합성+락 대조 (k6) — `feature/load-test-coupon`→dev. `coupon-claim.js`(setup서 회원200 가입/로그인 쿠키수집·per-vu-iterations 200동시 claim·정합성 게이트). **200명 동시·100장**: none=100/100/0·p95 1.34s, redis(DIY 스핀락)=발급52~93·503 ~107-148(50ms폴링+3s한도로 무너짐), redisson(pub/sub)=100/100/0·p95 2.76s. **초과 발급 0은 셋 다(DB 보증)·단일 인스턴스선 락=오버헤드·DIY<라이브러리**. README §B 기록. 후속=관측성·DIY 락 튜닝 재실험
- [x] DIY 락 튜닝 재실험 (k6) — `feature/diy-lock-tuning`→dev (397 tests). 락 파라미터 설정값화(`app.lock.redis.spin-interval-ms/wait-ms/lease-ms`·`DistributedLock.defaultWait/Lease`). 단일변수 실험: spin 50→5ms로 **503 123→40·발급 77→100**(폴링이 핸드오프 throttle 가설 확인·대가 Redis폴링↑), wait 3→15s로 **503 0이나 p95 3→8.4s**(대가 지연). Redisson pub/sub은 트레이드오프 없이 0/2.76s. README §B-2. 후속=관측성·배포
- [x] 관측성 (Prometheus + Grafana) — `feature/observability-grafana`→dev (**런타임 PASS**). `micrometer-registry-prometheus`→`/actuator/prometheus`(히스토그램 버킷·tomcat mbean·security 허용). docker-compose prometheus+grafana(opt-in profile `observability`)·`monitoring/`(스크레이프·provisioning·`commerce.json` 13패널). **3단 내러티브 대시보드**(①사용자경험 RPS/p95/에러 ②시스템 JVM/Hikari/Tomcat ③기능 캐시적중률/claim결과). 검증=타깃 up·Grafana 프로비저닝·패널 실데이터(캐시 96.7%). Grafana localhost:3001. 후속=알림룰·배포
- [x] 관측성 알림 룰 — `feature/observability-alerts`→dev `4c32528`(앱/테스트 무변경·397). `monitoring/alert.rules.yml` 6룰(InstanceDown·5xx율·p99>1s·Hikari pending·Tomcat 80%·상품캐시 적중률<50%)·`prometheus.yml` rule_files+compose 마운트. promtool SUCCESS·prometheus 6룰 로드 확인. 임계값 예시. 후속=Alertmanager 채널(Slack/메일)=외부
- [x] ADR 0014/0015/0016 (캐싱·분산락·관측성) — docs/private/adr(로컬·gitignore). 최근 결정 근거 기록(면접 자료). README 인덱스 갱신
- [x] (자율배치 06-29, 9커밋) 인기상품 캐싱 `eb75187`·대시보드 캐싱 `1da5e21`·인메모리 레이트리밋 `8a057ee`·FE 캐시패널 `f00ac47`·컨트롤러/NoOp락 테스트 `37df9c4`·레이트리밋 통합테스트 `1b8cc38`·**커서 상품 피드** `b2979c3`·페이지 size상한 `4cb9a71`·수동 캐시비우기 `3575fbe`. **410 tests**(401→410). 캐시/레이트리밋 테스트 OFF 토글로 기존 무영향. 보류=DB인덱스(Flyway 미검증·MySQL EXPLAIN 필요)·FE 피드 무한스크롤(UX 결정)
- [x] **V32·V33 MySQL 런타임 스모크 PASS** — `06-17` 재기동 시 Flyway v33 validate·`GET /api/categories` 200(parent_id)·product_image validate
- ⚠️ 공통 남음: 위 어드민 FE들(상품·카테고리·브랜드) **브라우저 확인**(서버 기동 완료 — http://localhost:3000/admin)
