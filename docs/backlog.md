# 백로그 (자율 진행 큐)

> CLAUDE.md "자율 진행 모드"가 이 파일의 **READY**를 위에서부터 막힐 때까지 처리한다.
> 각 READY = 결정이 끝나 바로 구현 가능한 1 step(외부 프로그램 연동 없음). 완료 시 DONE으로 옮기고 dev-log 기록.
> 외부 프로그램 연동(RabbitMQ·외부 API·새 외부 도구)은 자율 금지 → "함께(학습)"에서 사용자와 직접.

## READY (결정 완료 · 외부 무관 · 자율 진행 가능 · 위에서부터)
> 2026-07-12 전수 스캔(5영역·44후보, 파일/grep 근거)에서 추린 것. 위에서부터 처리.
- **재고 임박·품절 리포트** (M·BE+FE) — 재고 임계치 이하 옵션 집계 API + 어드민 위젯(QueryDSL 집계). 품절 방치 = 커머스 손실 1순위인데 대시보드는 매출만 본다.
- **감사로그 드릴다운 + CSV 내보내기** (M·BE+FE) — 감사는 "뽑아서 보관"이 용도인데 화면 조회만 가능. `GET /api/audit-logs/export`(text/csv·UTF-8 BOM·StreamingResponseBody) + 행 클릭 상세. 리포 전체에 CSV가 0개.
- **@RateLimit AOP 일반화 + 429 Retry-After** (M·BE) — `rateLimiter.check(...)`가 3곳에 손으로 박혀 있다. `@Auditable`처럼 애너테이션+Aspect(SpEL 키)로 승격 + Retry-After 헤더(현재 없음 → 클라가 재시도 시점을 모름).
- **리뷰 정렬·필터·평점 분포** (M·BE+FE·마이그0) — 상세가 첫 10건 최신순만 보여줘 리뷰가 쌓이면 못 읽는다. QueryDSL 동적정렬 + `GROUP BY rating` 분포.
- **JaCoCo 커버리지 리포트** (S·infra) — 433 테스트가 "어디를" 덮는지 모른다. 정산·부분환불처럼 돈 걸린 경로의 빈 구멍을 찾는 가장 싼 방법.
- **라우트 loading.tsx + metadataBase + 필터URL 색인 정책** (S·FE) — SSR 전환 후속 3종: 서버 fetch 대기 구간 스켈레톤 부재 / `metadataBase` 미설정(OG 상대경로 경고) / 필터 조합마다 자기참조 canonical → 중복 색인.

## 함께 (외부 연동 · 학습 — 자율 금지)
- 🚧 **배포 ($0 라이브 데모) — 경로 A(Oracle VM) 확정** — 준비물 완료: env화·`Dockerfile`·`docs/deploy.md`(`feature/deploy-prep`) + **prod 산출물·배선 보강**(`feature/deploy-prep-hardening`→dev `06e7ff8`): `backend/docker-compose.prod.yml`(앱+MySQL)·`.env.prod.example`·datasource `${SPRING_DATASOURCE_USERNAME:${MYSQL_USER}}` 체인·`APP_OAUTH2_REDIRECT` 행·`.gitignore` `.env.prod` 차단. 로컬 검증=`bootJar`·`next build` PASS. **결정=경로 A**(Oracle Always Free VM: Vercel FE + VM에 Spring Boot+MySQL; 콜드스타트 없음·진짜 MySQL·쿠키 first-party). ⚠️경로 B는 Koyeb 무료 폐쇄(Mistral 인수)로 약화. **다음=사용자 계정 단계**: Oracle A1 VM 생성→레포 clone→`.env.prod`→`docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build`→Caddy HTTPS→Vercel FE→쿠키전략(Vercel rewrites `/api/*` 프록시=first-party 추천)→로그인 확인. MySQL 실런타임 검증=VM 첫 기동. **VM 준비물 `deploy/`**(vm-setup.sh·Caddyfile·README) + 프록시 뒤 OAuth2 헤더(`server.forward-headers-strategy`) 추가(`feature/deploy-vm-runbook`→dev `2832ed5`). (가이드=docs/deploy.md §3, deploy/README.md)
- ✅ 아웃박스 P2b 실제 RabbitMQ (메시지 브로커) — `feature/outbox-rabbitmq`→dev (377 tests·**런타임 PASS**). 병행 opt-in(`outbox.publisher=in-process|rabbit`)·EventPublisher 포트 어댑터·@RabbitListener 소비·docker-compose rabbitmq(15672). 후속: Testcontainers 실브로커 통합·DLQ.
- 우편번호 검색 API (Daum/Kakao 외부 API)
- ✅ CI (GitHub Actions, `.github/workflows/ci.yml`) — 도입 완료. **다음=스케줄 무인 운영(이 위에)**. 후속: Testcontainers 실DB 통합·브랜치 보호 규칙.

## 결정 필요 (외부 무관이나 결정 미정 — 정하면 READY로)
- (비어 있음) — 외부 무관 후보 소진. 다음은 "함께(외부)" 학습 또는 새 기능 결정.

## DONE (완료 — 기록)
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
