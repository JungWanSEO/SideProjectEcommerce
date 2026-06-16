# 개발 일지 (Dev Log) — 인덱스

> 추가 / 결정 / 문제·해결이 생길 때마다 기록한다.
> 목적: 면접용 근거(개발기간·결정·문제해결 스토리) + Claude 그라운딩.
> 작성법은 `dev-log` skill 참고.

**구조(2026-06-05~):** 길이 관리를 위해 상세 기록은 **월별 파일**로 분리하고, 이 파일은 **인덱스**(타임라인 요약 + 핵심 결정 + 다음 작업)만 유지한다. 새 기록은 **현재 월 파일에 append**하고, 이 인덱스에 한 줄 요약을 추가한다.

- 📂 [docs/dev-log/2026-05.md](dev-log/2026-05.md)
- 📂 [docs/dev-log/2026-06.md](dev-log/2026-06.md)

---

## 📅 타임라인 — 2026-05 · [상세 →](dev-log/2026-05.md)

- **05-31 (1일차) 프로젝트 세팅** — Initializr(Boot 3.5.14·Java 21·Gradle), 도메인형 패키지, 하네스 초석(CLAUDE.md·docs·skills). *문제: Boot 4.0 실수 설치 → 3.5.14 재생성.*
- **05-31 (1일차) member 도메인** — 가입/조회 + global 기반(BaseEntity·ApiResponse·전역예외). *문제: 한글 UTF-8 깨짐(셸 인코딩) → 400/500 교정.*

## 📅 타임라인 — 2026-06 · [상세 →](dev-log/2026-06.md)

**15일차 (06-16)**
- **자율 #4 카테고리 2단계 계층화 (V33) · 백로그 READY 소진** — 이어진 `자율진행`으로 backlog 마지막 항목. BE=`category.parent_id`(ID참조·**V33**)+`CategoryResponse/CreateRequest.parentId`(편의 생성자로 호출부 무변경)+`CategoryService.create` 2단계 검증(부모없음/3단계 400). FE=`Category.parentId`+어드민 폼 카테고리 셀렉트 부모→자식 들여쓰기(`categoryOptions`). 테스트 +3 → **335**·FE 0. 머지 `3a1bc10`. ⚠️V33 스키마=**MySQL 스모크 복귀 후**. **🎉 백로그 READY 4개(#1~4) 전부 완료** → 다음 자율진행은 빈 READY라 멈춰 "백로그 채우기" 보고. 다음=#1~4 브라우저 확인·#3#4 MySQL 스모크·백로그 채우기 또는 스케줄 무인·dev→main.
- **CI(GitHub Actions) + 자율 배치 #1~3 (상품 어드민 CRUD 완성)** — 워크플로 심화(CI 먼저→스케줄 합의). **CI**=`.github/workflows/ci.yml`(백엔드 gradle test·H2 + FE tsc/lint, push/PR→dev/main 두 job·gradlew chmod·첫 런 초록불 확인) 커밋 `c55d728`. **자율 배치**(한 번의 `자율진행`): **#1 상품 수정 API**(PUT `/api/products/{id}` ADMIN·`Product.updateBasics`·마이그0·332 tests·`94298a5`) · **#2 어드민 상태·이미지 관리 UI**(상태 드롭다운+이미지 갤러리·`apiPatch` 추가·`6bd386d`) · **#3 어드민 등록·수정 폼**(POST 등록+PUT 수정·카테고리/브랜드 셀렉트·`fa7d30d`). **#4 카테고리 계층화(V33)는 context 길어 항목 경계서 멈춰 backlog에 남김**(완료분 git 영속→다음 자율진행이 새 context로 이어감=자율 설계 context 내성 실증). 발표 덱(docs/private 로컬)도 co-occurrence+CI 반영·PPTX 재생성. ⚠️#1~3 브라우저 확인·#4는 다음. 다음=`자율진행`(#4)·스케줄 무인·dev→main.
- **자율 진행 멀티스텝 배치 (백로그 ①②③)** — "1 step 위임은 자동화가 아니다"는 지적 → **계획(백로그)→연속 자율→배치 검토**로 전환. `docs/backlog.md` 신설 + CLAUDE.md 프로토콜 `1 step`→`READY 막힐 때까지 연속`·**외부 연동(RabbitMQ·외부 API·새 외부 도구)은 자율 금지=함께**. 한 번의 "자율진행"으로 외부 무관 3개를 dev까지: **①옵션 FE 어드민 UI**(`/admin/products`·머지 `6a43281`·FE tsc/lint 0) · **②상품 상태 변경 API**(PATCH status ADMIN·`1f14521`·마이그레이션0·322 tests) · **③대표 이미지 갤러리**(ProductImage·**Flyway V32**·ProductResponse.images[15-arg 편의 생성자로 기존 호출부 무변경]·ADMIN POST/DELETE images·FE ProductGallery·`7029973`·327 tests·FE 0). **③ MySQL 런타임 스모크 PASS**(복귀 후: V32→v32+validate·이미지 추가201/삭제200·net-zero·② 상태 PATCH도 PASS). ⚠️①③ 브라우저 확인·이미지 시드는 남음. 다음=백로그 더 채우거나 함께(외부) 학습.
- **상품 옵션 추가·수정 API (자율진행 첫 실행)** — 자율 진행 모드 첫 가동(로드맵에 결정된 step 없어 멈춤→사용자 후보 선택→자율 dev 병합). 상품 등록 시에만 넣던 옵션(사이즈/재고)을 등록 후에도 관리. **ADMIN** `POST /api/products/{id}/options`(중복 사이즈 409)·`PUT …/{oid}`(수정·404·409)·`DELETE …/{oid}`(404)→갱신 ProductResponse. 도메인=`ProductOption.update`+`Product.addOption(size,stock)`/`updateOption`/`removeOption`(애그리거트 루트가 불변식 소유), `ProductOptionUpsertRequest`, SecurityConfig 옵션경로 ADMIN 매처. **마이그레이션 0**(기존 product_option 재사용). 옵션 추가 시 새 id 위해 saveAndFlush. 테스트 +10(서비스6·컨트롤러4) → **318**. **(자율 병합) `feature/product-option-api`(`88e6fa9`)→dev `--no-ff` `6bd8d07`**(318 tests·push `ae4a807..6bd8d07`·브랜치 삭제). **MySQL 런타임 스모크 PASS(복귀 후)**: 추가201(saveAndFlush 새 id)→영속→중복409→수정200→404→검증400→삭제200(net-zero)→비ADMIN 403. ⚠️MySQL 컨테이너 Exited(255)→docker compose 재기동(volume 보존). 다음=필요 시 FE 옵션관리 UI.

**14일차 (06-15)**
- **데모 시드 빈(dev 프로파일): 카탈로그 분류 + 다중항목 주문** — 추천/함께 산 상품이 브라우저서 폴백 아닌 실제 데이터로 보이게(시드 갭 해소). 결정(사용자)=dev 프로파일 시드 빈(@Profile("dev") CommandLineRunner·멱등)+테스트 잔재(RecTestCat/RecProdA·B) 삭제 후 정식 분류 교체. `DemoDataSeeder`(잔재정리→카테고리5[아우터·상의·하의·신발·액세서리]·브랜드3→P01~P07 매핑[`Product.assignTaxonomy` 신규]→데모회원 demo1~3+**다중항목 PAID 주문 6건**→buyer 신호)+`DemoDataInitializer`(seed→추천배치→함께산상품배치, 별도빈 경유=프록시 트랜잭션). 스키마=Flyway/데모데이터=시드빈(책임분리)·운영선 빈 미생성. `run.ps1` SPRING_PROFILES_ACTIVE=dev. 시드 빈은 dev전용=테스트 무영향 **308 tests** 유지. **MySQL 런타임 PASS**(dev bootRun: 카테고리5·브랜드3·매핑·잔재삭제·주문6·**cooc 16행**·rec 6행, `together/2` cooccurrence=true·[Pants 2회·Cap·Tee] / buyer `/me` personalized=true). ⚠️콘솔 한글 mojibake=표시 artifact(JSON/DB UTF-8 정상). 시드는 데모 deliverable이라 유지. **(마일스톤) `feature/demo-seed`(`2013f7a`)→dev `--no-ff` 머지 `6de3a92`**(308 tests·push `4e47d1c..6de3a92`·브랜치 삭제). 다음=dev→main 사용자 수동·브라우저 확인은 사용자 몫.
- **개인화 추천 Step 3 (함께 산 상품·co-occurrence·상세 노출)** — 회원 무관 상품↔상품 통계("이 상품 산 사람들이 함께 산 상품"). 결정(사용자)=배치 미리계산+**새 `product_cooccurrence` 테이블**·같은 카테고리/브랜드 폴백(cooccurrence 플래그)·BE+FE. 알고리즘=PAID 주문 self-pair 집계(**`COUNT(DISTINCT order)`**·취소항목 제외·ON_SALE 후보·top10 지우고-다시-넣기)+`@Scheduled(매시30분)+@Transactional` 한 메서드. 엔드포인트 `GET /api/recommendations/products/{id}/together`(**public**)·`POST /cooccurrence/run`(ADMIN); **순환 의존 회피**로 ProductController 아닌 RecommendationController에 응집(읽기가 product enrich 사용=recommendation→product 단방향 유지). **Flyway V31**. FE=`CoOccurrenceSection`(상세 리뷰 아래·카드 재사용·cooccurrence면 "함께 산 상품"/폴백이면 "비슷한 상품"). 테스트 +6 → **308**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V31→v31·validate, 폴백 3종[같은카테고리/인기순/404]·다중항목 주문 2건 시드→배치 created=6→together/1 cooccurrence=true·**빈도순**[P02 2회>P04 1회]·캐노니컬 복원). ⚠️콜드스타트: 한 주문에 2+상품이라야 신호 발생→데모엔 다중항목 주문 시드 필요. **🎉 개인화 추천 전체(행동로그→배치→나를위한추천→함께산상품) 완성**(V29·V30·V31). **(마일스톤) `feature/recommendation-cooccurrence`(`b4fe9be`)→dev `--no-ff` 머지 `71f8855`**(병합트리 308 tests·push `4e195f1..71f8855`·브랜치 삭제). 다음=dev→main 사용자 수동.
- **개인화 추천 Step 1+2 dev 병합 (마일스톤)** — `feature/recommendation`(2커밋 `5cc4028`·`b65d784`)을 **`--no-ff` 머지 `048c317`**로 dev 통합. 병합 트리 **302 tests PASS** → dev push(`4989d83..048c317`)·브랜치 삭제 → dev/main만. dev→main은 사용자 수동. **🎉 개인화 추천(행동로그→배치→나를 위한 추천) Step 1+2 완성**(V29·V30). 다음=Step 3(함께 산 상품)·시드 카테고리 부여·발표 다이어그램 반영.
- **개인화 추천 Step 2 (추천 배치 + 나를 위한 추천)** — 규칙 기반 v1. 신호=구매×3·찜×2·조회(30일)×1 → 카테고리/브랜드 친화도 → 인기 후보(산/찜 제외) → top-10 저장. 새 `recommendation` 도메인(score double·rank 회피)+`RecommendationBatchService.run()`(**@Scheduled+@Transactional 한 메서드**=self-invoc 회피)+`RecommendationService`(폴백 인기순)+`GET /me`(인증)·`POST /run`(ADMIN). **Flyway V30**. FE=홈 `RecommendedSection`(personalized="나를 위한 추천"/폴백="인기 상품"). 테스트 +4 → **302**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V30→v30, 권한 403/200·콜드스타트 폴백·**개인화 created=2·sibling 추천** [시드 카테고리 전무라 테스트상품 생성해 검증]). ⚠️테스트데이터(RecTestCat/Brand·상품21·22) 잔존(DELETE 차단)=유일 카테고리 상품·시드 갭. `feature/recommendation`. 다음=Step 3(함께 산 상품).
- **개인화 추천 Step 1 (행동 로그·조회 도메인)** — Phase 2 남은 기능 착수. 결정(사용자)=둘 다(나를 위한 추천+함께 산 상품) 점진·배치·조회 로그 추가·BE+FE. 전체=행동 신호(조회<찜<구매)→가중 집계 배치→추천→홈/상세. 3단계 중 ①. 새 `activity` 도메인: `ActivityLog`(member·product·**type enum(VIEW)** append-only)+`ActivityLogService.logView`(상품404)+`POST /api/activity/views`(로그인). 찜·구매는 기존 테이블서 읽어 중복 안 둠. **Flyway V29**(activity_log+`(member_id,created_at)` 인덱스). FE=상세 진입 시 조회 기록(best-effort). 테스트 +2 → **298**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V29→v29, 비로그인401→기록201·append→없는상품404→누락400→DB 4행 distinct 2). 활동로그는 Step 2 시드로 보존. `feature/recommendation`. 다음=Step 2(추천 배치+나를 위한 추천).
- **발표 자료 비공개화 + 슬라이드 생성기 이미지 지원** — 결정(사용자)=아키텍처 설명 자료(다이어그램·ADR·덱)는 공개 리포 대신 **로컬 전용 `docs/private/`(gitignore)** 로 관리(강점 노출 방지). 산출물 이동 후 삭제 커밋 `1646a59`로 공개 HEAD 제거(과거 SHA엔 잔존·사용자 수용). `build_slides.py`에 **이미지 임베드(`![](png)`)** 지원 추가(폭 맞춤·미배치 시 자리표시자)·README 갱신. 로컬에 발표 덱 `architecture-deck.md`(9슬라이드)+작업 README. 빌드 검증 PASS·기존 덱 회귀 없음. 다이어그램 확인=mermaid.live/VS Code 확장. 다음=PNG 내보내 PPTX 완성.
- **위시리스트(찜) dev 병합 (마일스톤)** — `feature/wishlist`(1커밋 `0abf6a5`)을 **`--no-ff` 머지 `ac515db`**로 dev 통합(사용자 브라우저 확인 후 지시). 병합 트리 **296 tests PASS** → dev push(`f8fd798..ac515db`)·merged 브랜치 삭제 → 브랜치=dev/main만. dev→main은 사용자 수동. **🎉 위시리스트(찜) 완성**(Phase 2 남은 기능; 찜=인기도 신호=다음 개인화 추천의 첫 행동 로그).
- **위시리스트(찜) 도메인 (백엔드+FE)** — Phase 2 남은 기능. 추천 디딤돌(인기도 집계→행동 신호). 구조는 `review` 쌍둥이. 결정=명시적 add/remove(REST)·BE+FE 한 번에. `Wishlist`(member·product UNIQUE)+`WishlistService`(add 404/409·remove 404·내목록·내 찜ID)+`WishlistController`(`/api/wishlist` POST·DELETE/{pid}·GET /me·/me/product-ids, 회원만). **Product.wishlistCount** 비정규화 카운터(원자 증감, review 패턴)+`ProductService.getProductMap`(enrich 단일화)+인기순 정렬(`sort=wishlistCount`). Flyway **V28**. FE=`WishlistProvider`/`useWishlist`(전역 찜 ID 집합)·`WishlistButton`(하트 overlay/inline)·목록 카드 하트+인기순+♥N·상세 찜버튼·`/account/wishlist` 그리드·Header 링크. 테스트 +5 → **296**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V28→v28, 추가201[enrich]→중복409→없는상품404→wishlistCount1→인기순top→해제200·count0→안찜404→캐노니컬복원; ⚠️PS `$pid` 예약변수 함정). `feature/wishlist`. 다음=커밋→dev 병합(마일스톤).
- **Phase 2 후속: payout 지급 단위** — 셀러 정산 항목을 기간으로 묶어 한 번에 지급. `Payout`(셀러·기간·합계·status PENDING→PAID)+`SettlementEntry.payoutId`(V23), `PayoutService`(생성/지급/조회), `PayoutController`(ADMIN)+셀러 `GET /api/seller/me/payouts`. per-entry 입금처리와 공존(묶음 포함 항목은 409). FE 어드민 `/admin/payouts`(생성·지급)+셀러 콘솔 "내 지급 내역". 테스트 +12 → **247**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V23→v23, 묶음 생성→per-entry 409→지급→PAID_OUT, 캐노니컬 복원). `feature/seller-console`. 다음=부분환불 안분.
- **Phase 2 후속: 부분환불 안분** — 주문 항목(라인) 단위 취소·환불 + 정산 역분개 상계. `OrderItem.status`(ACTIVE/CANCELLED)·`Payment.refundedAmount`(부분환불 누적)·`POST /api/orders/{id}/items/{itemId}/cancel`. 정산 `run()`은 활성 항목만, **`reverseRefunds()`** 가 정산 후 취소분을 음수 항목으로 상계(diff·멱등). Flyway **V24**(+settlement UNIQUE 제거). FE 주문상세 항목별 취소 + 어드민 "환불 상계". 테스트 +3 → **250**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V24→v24, 멀티셀러 주문→부분취소→상계→S2 net 0·S1 불변, 캐노니컬 복원). `feature/seller-console`. 다음=마일스톤(dev 병합).
- **Phase 2 셀러별 정산 후속 dev 병합 (마일스톤)** — `feature/seller-console`(3커밋: 셀러콘솔·payout·부분환불)을 **`--no-ff` 머지 `f249628`**로 dev 통합. 병합 트리 **250 tests PASS** 후 dev push(`fd6c2ec..f249628`), merged 브랜치 정리(로컬·원격 삭제) → 브랜치=dev/main만. dev→main은 사용자 수동. **🎉 Phase 2 셀러별 정산(코어 1a~3 + 후속 3종) 전체 완성**(Flyway V18~V24).
- **쿠폰/프로모션 Step 1 (코어 + 체크아웃 적용)** — 새 `coupon` 도메인. 4축 결정(정액+정률·코드 입력형·분담 PLATFORM/SELLER·범위 sellerId nullable). `Coupon`(할인계산·검증 엔티티)+enum 3종+`CouponService`(정규화·중복·applyCoupon)+`CouponController`(ADMIN). 핵심=**gross 보존 + payable**(`Order.discountAmount`·`couponCode` 스냅샷, `payableAmount`=총액−할인, 결제가 payable로 청구; 항목 원가는 정산 Step 2가 안분하도록 보존). `CheckoutRequest.couponCode`·OrderProcessor 적용. Flyway **V25**. 테스트 +24 → **274**. **정적+MySQL 런타임 PASS**(V25→v25+validate, 쿠폰 체크아웃 total20000·discount5000·payable15000·결제 amount15000·코드 정규화·존재X 400, 캐노니컬 복원). **커밋 `feature/coupon` `c2ec6af`**.
- **쿠폰/프로모션 Step 3 (회원 쿠폰함 + 하이브리드)** — 코드만 맞으면 누구나·무제한 쓰던 한계 → 발급·보유·단일사용. 결정=**하이브리드**(`Coupon.issueType` PUBLIC 공개코드[Step 1]/ISSUED 발급형[지갑·단일사용])·체크아웃 지갑 드롭다운·취소 시 복원. `MemberCoupon`(member·coupon UNIQUE·UNUSED/USED) + 새 `MemberCouponService`(발급/지갑/apply·preview·release), `CouponService`는 계산기로 분리(findByCode·calculateDiscount). OrderProcessor·PaymentService(취소→release) 연동. `POST /api/coupons/{id}/issue`·`GET /api/member-coupons/me`. FE=쿠폰함 `/account/coupons`·체크아웃 드롭다운·어드민 배포방식/발급. Flyway **V27**. 테스트 +8 → **291**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V27→v27+validate, 발급→지갑UNUSED→사용→USED→**재사용 400**→취소→복원UNUSED·공개형은 발급없이 코드만 적용). `feature/coupon-wallet`. 다음=커밋→dev 병합(마일스톤).
- **쿠폰/프로모션 Step 2b (부분환불×할인 일관성)** — 할인 주문 항목 취소 시 환불액·정산 상계 정합. 문제=환불이 항목 gross라 **과다환불**+할인주문 상계 skip. 해법=**항목별 할인 안분**(`Order.discountShares` 매출비례·잔차최대항목) → 항목 **실효가=소계−share**가 환불·정산의 단일 출처 → 어떤 취소 순서에도 Σ실효가=결제액. `cancelOrderItem` 환불=실효가, `run` 항목별 재설계(allocateDiscount 제거)·`reverseRefunds` skip 제거+할인델타·net 환원 음수 상계. **마이그레이션 0**(파생). 테스트 +5 → **283**. **런타임 PASS**(P01·P02 안분 share 1000/5000 → P01 부분취소 **환불 9000=실효가**[과다환불 해결] → 역분개 gross−9000·discount−1000·net−8875·Σgross45000 → 대사 STATUS_MISMATCH="정산 후 환불" 정상 감지). 다음=커밋→dev 병합(마일스톤).
- **쿠폰/프로모션 Step 4 (FE + 미리보기 엔드포인트)** — 쿠폰을 화면 끝까지: 체크아웃 코드 입력+**주문 전 할인 미리보기**(읽기전용 `POST /api/orders/coupon-preview`)·결제/주문상세 결제액 분해·어드민 `/admin/coupons`(발급 폼+목록+사이드바)·정산 화면 할인 컬럼(어드민·셀러 콘솔). FE types/헬퍼(lib/coupon.ts). 백엔드 +2 → **278 tests**·FE **tsc/lint 0**. **런타임 스모크 PASS**(미리보기 정상 discount4000·payable16000 / 최소금액 미달 400, 스키마 변경 없음). 스토어=웜 부티크·어드민=그레이 톤. 브라우저는 사용자 몫. 다음=Step 4 커밋→기능 dev 병합 · Step 2b/3.
- **쿠폰/프로모션 Step 2 (정산 분담)** — 할인을 셀러별 정산에 반영("할인을 누가 부담하나"=PG수수료 안분·플랫폼수수료에 이은 3번째 1급 회계). 결정=**grossAmount는 할인 후 셀러 몫**(Approach X)→대사 group-by-sum 그대로 MATCHED·수수료=payable 기준 / 부분환불 상계는 Step 2b 분리. **할인 안분**(플랫폼와이드 매출비례 `proRate`·셀러한정 전액) + **net=gross−수수료+(PLATFORM부담이면 할인 환원)**: SELLER부담=셀러 net↓(셀러 부담)·PLATFORM부담=셀러 무손실(플랫폼 마케팅비). `Order.couponFundedBy/couponSellerId` 스냅샷·`OrderService.getOrderDiscount`(+DTO)·`SettlementEntry.discountAmount/discountFundedBy`(net subsidy·scheduled 오버로드 유지)·DTO 확장. **대사 무변경**. Flyway **V26**. 테스트 +2 → **276**. **정적+MySQL 런타임 PASS**(V26→v26+validate, E2E: 셀러귀속 2만+플랫폼부담 3천쿠폰→gross17000=payable·discount3000·**net17875**[환원]→**대사 MATCHED**[Σgross=PG금액]·DB 분담스냅샷, 캐노니컬 복원). **다음=Step 2b(환불 상계 할인 재안분)/Step 3 쿠폰함/Step 4 FE**.

**13일차 (06-14)**
- **Phase 2 셀러별 정산 Step 3** — 셀러 정산서 조회: QueryDSL `search`(셀러·상태·기간 필터)+`summarizeBySeller`(셀러별 집계·sellerName enrich), `GET /api/settlements?sellerId=&status=&from=&to=` + **`GET /api/settlements/summary`**. 어드민 `/admin/settlements` 강화(셀러별 정산서 테이블·셀러/기간 필터·플랫폼수수료 컬럼·KPI 5카드). 마이그레이션 0(쿼리/DTO/엔드포인트). 테스트 +6 → **222**, FE tsc/lint 0. **정적+MySQL 런타임 PASS**(Docker 재기동 후: summary 셀러별 집계·sellerName, sellerId/status/기간 필터 확인, 캐노니컬 복원; 브라우저 화면만 사용자 몫). 다음=마일스톤(feature/seller-settlement→dev).
- **Phase 2 셀러별 정산 dev 병합 (마일스톤)** — `feature/seller-settlement`(5커밋, 5 ahead/0 behind)를 **`--no-ff` 머지 `902509b`**로 dev 통합. 병합 트리 **222 tests PASS** 후 dev push(`0f69e7c..902509b`). dev→main 승격은 **사용자 수동**(범위 밖), feature 브랜치 보존.
- **Phase 2 후속: 셀러 로그인 콘솔 (Role.SELLER)** — 셀러가 자기 정산서만 조회. 백엔드=`Role.SELLER`+`Member.sellerId`(V22)+ADMIN 운영자 지정(`PUT /api/sellers/{id}/owner`)+셀러 self API(`/api/seller/me/**`, sellerId 스코핑·hasRole SELLER). FE=전용 `/seller` 콘솔(로그인 라우팅·게이팅·정산서 KPI·읽기전용 테이블). 병합된 feature 2개 정리(삭제). 테스트 +13 → **235**·FE tsc/lint 0. **정적+MySQL 런타임 PASS**(V22→v22, 운영자 지정→셀러 로그인→내 정산만·권한 403/401, 캐노니컬 복원·down). `feature/seller-console`. 다음=후속(payout·부분환불) 또는 dev 병합.

**12일차 (06-13)**
- **Phase 1 마일스톤 dev 병합** — `feature/fe-design-polish`(Phase 1 + 웜 부티크 디자인, dev 대비 10 ahead/0 behind)를 **`--no-ff` 머지 커밋 `74651e4`**로 dev 통합. squash 대신 머지(기존 dev 관례·granular 히스토리 보존). 병합 트리 **188 tests PASS** 재확인 후 dev·feature origin push. ⚠️로컬 `.vscode/settings.json`(skip-permissions)은 병합 제외(이후 `.gitignore` 처리 `0f69e7c`). 다음=**dev→main 승격(PR)**.
- **Phase 2 셀러별 정산 Step 1a** — Phase 2 착수. 매핑 워크플로(4영역 병렬 리딩)로 설계 확정 → 새 `seller` 도메인(seller 1:N brand·풍부 필드·정지/재개) + `Brand.sellerId` 매핑(`PUT /api/brands/{id}/seller`) + Flyway **V18/V19**. 결정=새 seller 도메인·결제를 셀러별 분할·PG수수료 안분+플랫폼 수수료·ADMIN 시작. 테스트 +24 → **212**. **정적+MySQL 런타임 PASS**(V18/V19→v19·validate, brand→seller 1:N 귀속·권한 403/401, 캐노니컬 복원). `feature/seller-settlement`. 다음=Step 1b(OrderItem 셀러 스냅샷).
- **Phase 2 셀러별 정산 Step 1b** — `OrderItem`에 brandId·sellerId **주문 시점 스냅샷**(OrderProcessor가 상품→브랜드→셀러 도출) + Flyway **V20**. 테스트 +2 → **214**. **정적+MySQL 런타임 PASS**(V20→v20·validate, 체크아웃 시 주문 항목 셀러 스냅샷 + **🔑 브랜드 재귀속해도 기존 주문 sellerId 불변=이력안전 시연**, 캐노니컬 복원·앱/컨테이너 down). 다음=Step 2(셀러별 정산 분해+대사 재작성).
- **Phase 2 셀러별 정산 Step 2 (핵심)** — 결제를 **(결제×셀러)로 분해**: `SettlementService.run()`이 주문 항목을 sellerId별 gross 합산→**PG수수료 안분**(잔차 최대셀러)+**플랫폼수수료**(Seller.commissionRate)→셀러별 정산항목, bySeller 응답. `SettlementEntry`+sellerId/platformFee/platformFeeRate·UNIQUE 복합(payment_id,seller_id). **대사 group-by-sum 재작성**(OursTx). Flyway **V21**. 테스트 +2 → **216**. **정적+MySQL 런타임 PASS**(V21→v21, 멀티셀러 E2E: 3만 주문→S1/S2 분해[net 8750/18500]→**대사 MATCHED·불일치0=group-by-sum 증명**, 캐노니컬 복원·down). **🎉 "매출≠셀러 실수령" 1급 모델링 = Phase 2 셀러별 정산 핵심 완성.** 다음=Step 3(셀러 정산서 조회+어드민 FE).

**2일차 (06-02)**
- **member 테스트** — Service 단위 + Controller 슬라이스 (9개)
- **Repository 테스트** — @DataJpaTest, 테스트 피라미드 3층 (13개)
- **product 도메인** — 등록/조회. 가격 `long`·상태 enum·**애그리거트 간 ID 참조** 방침
- **product 테스트** — enum round-trip 검증 (23개)
- **order 도메인** — 생성/조회/취소, 가격 스냅샷, 재고 차감/복원 (36개)
- **cart 도메인** — 담기/조회/제거, order(스냅샷)와 대비되는 **라이브 참조** (48개)
- **MySQL 전환** — Docker MySQL 8(포트 **3307**), 테스트는 H2로 분리
- **API 문서화** — springdoc-openapi(Swagger)
- **인증 Phase 1** — Spring Security + JWT(STATELESS) + 역할 + BCrypt (53개)
- **인증 Phase 2** — Refresh 토큰 DB저장·회전(rotation) + `jti` 유일성 (58개)
- **모노레포 재구조** — `backend/` + `frontend/` 분리
- **재고 동시성** — `@Version` 낙관적 락 + spring-retry(새 트랜잭션), `OrderConcurrencyTest` (59개)
- **통합 시나리오 테스트** — 보안 필터 ON·실제 JWT E2E (60개)
- **상품 목록·페이징** — 커스텀 `PageResponse`, 기본 createdAt DESC (64개)

**3일차 (06-03)**
- **상품목록 런타임 검증 + 🚨 랜섬웨어 사고·복구** — 외부 노출 MySQL 침해 → 볼륨 폐기·비번 강화·포트 `127.0.0.1` 바인딩
- **내 주문 목록** — 본인 주문 페이징, N+1 = `default_batch_fetch_size` (68개)
- **주문 IDOR 보강** — 소유자/ADMIN 검증 없으면 403 (73개)
- **기술비교 슬라이드 파이프라인** — Markdown → PPTX 생성기(python-pptx)
- **상품 검색/필터** — QueryDSL 동적 where(키워드·가격대) (75개)
- **주문 요약 DTO** — 목록=요약 / 상세=전체
- **카테고리·브랜드 도메인 (2a)** — 대칭 도메인 신설 (87개)
- **카테고리·브랜드 ↔ Product (2b)** — Long FK + 검색필터 + 이름 enrich (88개)
- **상품 옵션(사이즈) 설계 합의** — 단일 축(사이즈), 색상=별도 상품
- **상품 옵션 P1** — 재고/`@Version`을 `ProductOption`으로 이동, `Product.stock` 제거, 주문 optionId 컷오버
- **옵션 P1 런타임 검증 + 레거시 컬럼 정리** — FREE 옵션 시드, 죽은 `stock`/`version` 컬럼 DROP(런타임 검증으로 발견)

**4일차 (06-04)**
- **옵션 P3 장바구니** — optionId 기준(같은 상품 다른 사이즈=별개 항목), 응답 size/stock/soldOut
- **프론트엔드 착수** — React+TS+Next.js 스택 결정, Next 15.1 스캐폴딩
- **FE 1차** — SecurityConfig CORS + 상품 목록 페이지(첫 FE↔BE 연동)
- **FE 2차** — 상품 상세(동적 라우트 `/products/[id]`)
- **인증 Phase 3** — JWT를 **httpOnly 쿠키**로 전환 + 로그인 UI (90개)
- **FE 장바구니** — 상세 담기 + `/cart` 페이지
- **체크아웃** — 서버 트랜잭션 방식 A(`POST /api/orders/checkout`) (93개)
- **FE 주문 목록·상세·취소** — 🎯 구매 전체 흐름 FE 완성
- **운영 하드닝** — 시크릿 OS env(12-factor) + Flyway 도입(V1, ddl `validate`)
- **인증 마무리** — 401 EntryPoint / 403 핸들러 + FE 자동 refresh
- **OAuth2 대비 Member prep** — Flyway **V2**(첫 실제 마이그레이션), provider/providerId

**5일차 (06-05)**
- **git/GitHub 정리 + 브랜드명 제거 + dev-log 월별 분리** — repo SideProjectWeb, main/dev + PR 워크플로(CONTRIBUTING.md), 무신사→패션 커머스
- **결제 도메인 설계 합의** — 모의 PG(포트-어댑터) · 재고 차감=결제 승인 시점(OrderStatus PENDING/PAID) · 멱등성 · Redis/MQ 확장지점 (상세 architecture.md §13)
- **결제 P1·P2** — payment 골격(상태머신·포트어댑터·V3) + 주문 흐름 전환(OrderStatus PENDING/PAID, 재고 차감→결제 시점, V4) (96 tests)
- **결제 P3** — `POST /api/payments`(모의 PG·멱등성), PaymentService 오케스트레이터(재고차감 위임), HTTP 구매 흐름 완성 (104 tests)

**6일차 (06-07)**
- **결제 P4 (취소·환불)** — 주문 취소 시 PG 환불 + Payment CANCELLED 연동. `PaymentGateway.refund` 포트, `PaymentService.cancelOrder`(결제→주문 한방향·@Transactional 원자성), 단일 취소 엔드포인트 유지 (107 tests). **MySQL 런타임 검증**(Flyway V1~V4·validate, 결제·환불 흐름 PASS)
- **FE 결제·취소 화면 (P5)** — `ORDERED` 기준이라 끊겨 있던 구매 흐름 복구. 결제 화면 신설(`/orders/[id]/pay`, 멱등키 `crypto.randomUUID`), 체크아웃→PENDING→결제→PAID→취소(환불), `OrderStatus` 3상태 동기화. 브라우저 E2E 검증 PASS
- **아키텍처 학습 노트(멘토 토픽)** — `docs/architecture-basics.md`(왜 아키텍처?·의존성 방향·DI·그림: 계층형 vs 헥사고날/오니언) + `docs/payment-architecture-study.md`(Payment 도메인에 적용한 before/after: Impl관습·헥사고날·Clean(HttpStatus 침투 위반)·Feign/MSA) + `docs/payment-modern-architecture.md`(옛날 동기 DLL→현대 결제: 웹훅 흐름을 우리 코드에 그림·서명/멱등/금액 3대 방어·inbound전환; 단계별 누적, 다음=대사/정산)

**7일차 (06-08)**
- **git 정리** — 결제 P1~P5 dev 병합(PR #2) 확인 + 아키텍처 노트 `docs`→dev 머지(`--no-ff`) + stale 브랜치(docs·feature/payment) 삭제. Claude가 git 직접 실행(머지/푸시) 위임 시작.
- **정산(Settlement) P1** — 결제 심화 착수. 새 도메인 `settlement/`(SettlementEntry: gross/fee/**net=실입금**·SCHEDULED→PAID_OUT·조인키 pgTransactionId), **배치 스캔**(PAID 결제→정산 항목, T+2)·수수료 2.5%·ADMIN API(run/list/payout)·Flyway V5·멱등(payment_id UNIQUE). "매출≠결제액" 1급 모델링 (112 tests). **MySQL 런타임 검증 PASS**(Flyway V5/validate·run/payout/멱등·ADMIN 403).
- **정산(Settlement) P2 — 대사(reconciliation)** — 두 진실의 출처(우리 정산 ↔ PG 리포트)를 `pgTransactionId`로 대조. `PaymentGateway.fetchSettlements()` 포트 + **상태 보유 Mock 원장**(독립 출처), `ReconciliationService`가 5분류(MATCHED/MISSING_IN_PG/MISSING_IN_OURS/AMOUNT_MISMATCH/STATUS_MISMATCH)→`Mismatch` 스냅샷 저장, ADMIN API(run/mismatches)·Flyway V6 (118 tests). **MySQL 런타임 검증 PASS**("정산 후 환불=STATUS_MISMATCH"·"정산 후 결제=MISSING_IN_OURS" 자연 발생).
- **정산(Settlement) P3 — 불일치 해소(resolve) 워크플로** — 예외 큐를 검출→처리까지. `Mismatch`에 `MismatchStatus`(OPEN→RESOLVED/IGNORED)+사유, `reconcile()`이 OPEN만 스냅샷·처리된 거래키는 재대사에서 안 깨움(`alreadyHandled`), ADMIN API(resolve/ignore·status 필터)·Flyway V7 (123 tests). **MySQL 런타임 검증 PASS**(resolve/ignore 후 재대사=total 0·alreadyHandled 2, 결정 보존).
- **FE 어드민 콘솔(정산·대사 화면)** — 스토어와 분리된 `/admin` 라우트 그룹 + 사이드바 셸. 정산 화면(배치 실행·입금 처리·gross/fee/net KPI), 대사 화면(대사 실행·불일치 테이블·resolve/ignore·상태 탭). 결정: **접근제어 3겹**(백엔드 hasRole=진짜 경계 / 프록시·WAF IP제한 / 프론트 게이팅=UX), 손수 Tailwind(shadcn 미도입). **브라우저 E2E PASS**.
- **이벤트·아웃박스 P1** — 결제완료를 **트랜잭셔널 아웃박스**로 안정 발행(dual-write 해소). 설계노트(`event-outbox-design.md`) + 구현: `OutboxEvent`/폴러(`@Scheduled`)/`EventPublisher` 포트/`PaymentCompletionRecorder`(결제저장+이벤트 한 tx)/알림 핸들러(`NotificationLog` event_id UNIQUE=멱등)·Flyway V8·V9 (130 tests). 결정: self-invocation 회피 위해 발행/폴러를 별도 트랜잭션 빈으로, at-least-once→멱등 소비. **MySQL 런타임 검증 PASS**(결제→outbox PENDING→폴러 PUBLISHED→알림, 재발행 멱등).
- **이벤트·아웃박스 P2a** — 폴러 신뢰성·스케일아웃 보강: **지수 백오프**(2→4→8→16s·`next_attempt_at`)+데드레터, **`FOR UPDATE SKIP LOCKED`** 행 클레임(다중 폴러 중복 발행 방지)·Flyway V10 (131 tests). 결정: 백오프 정책은 프로세서·엔티티는 저장만, SKIP LOCKED는 native(H2 미지원→MySQL 런타임으로). **MySQL 런타임 검증 PASS**(백오프 카운트다운→FAILED 데드레터·SKIP LOCKED 발행 경로).
- **다중 PG MPG-1** — 포트-어댑터를 어댑터 2개+라우터로 증명: **토스/카카오 모의 어댑터**(공통 `AbstractMockPaymentGateway` DRY) + **`PaymentGatewayRouter`**(provider 레지스트리·null→기본·미지원 400, 환불은 저장된 provider로 라우팅), `Payment.provider`·Flyway V11, 대사도 `fetchAllSettlements()` 집계로 전환 (136 tests). 결정: 라우팅=클라이언트 선택+레지스트리(페일오버는 스트레치), provider는 String. **MySQL 런타임 검증 PASS**(KAKAO/TOSS 결제·미지원 PG 400(행 안 남김)·환불 라우팅·2 PG 대사 집계).

**8일차 (06-09)**
- **다중 PG MPG-3 — 정산 PG별 수수료율** — `SettlementPolicy`를 provider별 요율 **Map**(TOSS 2.5%·KAKAOPAY 2.8%·폴백 3.0%)으로, `SettlementEntry`에 **provider + feeRate 스냅샷**(OrderItem 가격 스냅샷 패턴), 정산 결과에 **PG별 분해**(`byProvider`), Flyway V12 (138 tests). "매출≠결제액"에 **PG 차이**를 연결. 결정: 요율 출처=상수 Map(static util 유지), feeRate=double(돈 아닌 비율). **MySQL 런타임 검증 PASS**(같은 10,000원 → TOSS 250 vs KAKAOPAY 280, V12+validate, `fee_rate` double 저장).
- **다중 PG MPG-2 — 대사 PG별 분류/표시 강화** — 대사 불일치를 **어느 PG의 거래인지**로 분류·필터·표시. `PgSettlementRecord`에 `provider`(어댑터가 자기 `provider()` 기록 — 거래ID 프리픽스 `KAKAO-`≠provider `KAKAOPAY`라 프리픽스 파싱 불가), `Mismatch`에 provider(Flyway V13), `reconcile()`이 거래키별 provider 도출(우리=`SettlementEntry.provider`, PG측=리포트 provider)+**PG별 분해**(`byProvider`, 알파벳순), 불일치 목록에 **provider 필터**(`?provider=`, 대문자 정규화) (143 tests). **MySQL 런타임 검증 PASS**(2 PG 시나리오: TOSS matched+missingInOurs / KAKAOPAY statusMismatch, byProvider 분해·provider 필터·V13+validate).
- **다중 PG MPG-stretch — 라우터 페일오버** — 요청 PG가 장애(설정상 down)·승인 거절이면 **다른 PG로 자동 대체**해 결제 성공률 방어. `PaymentGatewayRouter.approveWithFailover()`가 요청 PG 먼저→실패 시 나머지 PG(알파벳순) 순차 시도, **실제 승인한 PG**를 `PaymentRoutingResult`로 반환해 Payment에 기록(환불도 그 PG로). `payment.unavailable-providers`(설정/env)로 점검 PG 지정. `PaymentService.pay`는 한 줄 교체(전략은 라우터에 가둠), Flyway 불필요 (149 tests). **MySQL 런타임 검증 PASS**(KAKAOPAY down → 요청 KAKAOPAY가 TOSS로 페일오버 승인·Payment.provider=TOSS, 미지원 PG 400, WARN 로그 관측). **오답노트 스킬 신설**(`.claude/skills/mistake-log/` — 운영 함정 기록·참고).

**11일차 (06-12)**
- **Phase 1 #2 리뷰·평점 도메인 (백엔드+FE+수정)** — 새 도메인 `review/`(Review: memberId·productId ID참조·rating 1~5·content·사진 imageUrl·1인1상품1리뷰 UNIQUE). 작성/목록/**수정**/삭제 API. 결정: **구매자만**(PAID 주문 보유 검증)·**평점 비정규화 카운터**(Product.ratingCount/Sum, 원자 `@Modifying` 증감/델타)·핵심+사진리뷰·**수정은 작성자만**(삭제는 ADMIN도). Flyway **V15**, ProductResponse 평점 노출. **FE**: Stars(표시/입력)·목록 카드 별점·상세 평점요약+리뷰목록+작성/인라인수정 폼(로그인·구매자 게이팅). 테스트 +14→**166**. **정적+MySQL 런타임 PASS**(미구매403/구매201/집계/중복409/삭제후0/수정 시 avg 갱신·작성자아님 403). 🐞**런타임서 버그 발견·수정**: `@Modifying(clearAutomatically)`가 flush 전 컨텍스트 비워 보류 삭제 유실 → `flushAutomatically=true`(오답노트). 백엔드 **커밋 `94b6304`** + FE/수정 **커밋 `441c12e`·push**.
- **Phase 1 #3 패션 필터/검색/정렬 UI** — 백엔드 QueryDSL에 **사이즈 필터**(옵션 EXISTS, 재고>0; 파라미터 `optionSize` — `size`는 Pageable과 충돌) + **평점평균 정렬**(컬럼 없이 CASE 식, 리뷰0은 맨 뒤). FE `products` 페이지에 **검색바·정렬 드롭다운·카테고리/브랜드·가격대·사이즈 칩·초기화**. 정렬=최신·가격↓↑·리뷰많은순·평점높은순. 테스트 +2→**168**. **정적+MySQL 런타임 PASS**(필터·정렬 정확). ⚠️시드 옵션 전부 FREE라 사이즈 칩은 FREE만.
- **PLP UX 리서치 + 필터/검색/드롭다운 재설계** — 사용자 피드백(콤보박스 클릭시 네모·긴 검색바)→**deep-research**(Baymard·NN/g, `wf_73ce419a-614`, `docs/plp-ux-research.md`). 적용: **Headless UI `Listbox` 드롭다운**(`ui/Select` — 펼친 패널도 둥글게+접근성 내장; 사용자가 "펼친 패널도 디자인 맞춰야" 지적해 Shell→Headless로, 스택 `@headlessui/react` 승인 추가), **컴팩트 인라인 검색**, **적용 필터 칩 overview**(✕+전체해제), 가로 툴바 유지·데스크톱 즉시적용. FE tsc/lint 0·렌더 200. (#3+재설계는 이후 **커밋 `b71ded8`**.)
- **Phase 1 #4 Step 1 — 장바구니 수량 변경** — #4(체크아웃 완성)를 단계별로(사용자 결정: ①수량→②주소록→③주문서). 백엔드 `PUT /api/carts/items/{optionId}`(절대값 set ↔ 담기=가산) + `CartItem.changeQuantity`/`Cart.updateItemQuantity`/DTO, FE 장바구니 **−/+ 스테퍼**(재고 클램프). 결정: 재고는 BE에서 안 막음(담기와 동일 라이브 성격), 영속 cart는 dirty-checking flush(save 불필요). 테스트 +5→**173**. **정적+MySQL 런타임 PASS**(PUT 3→qty3/소계180k, GET 재조회=3로 flush 영속 확인, 0→400·없는옵션→404). **커밋 `4d4c41e`**.
- **Phase 1 #4 Step 2 — 배송지(주소록) 도메인 + FE** — 새 `address` 도메인(add-domain): Address(memberId ID참조·수령인·전화·우편번호·주소1/2·isDefault) + CRUD + **전용 set-default 엔드포인트**, AddressService가 **기본배송지 1개 불변식**(첫주소 자동기본/단일화/기본삭제 시 승격)·IDOR(403). Flyway **V16**(is_default=`bit(1)`). FE 독립 `/account/addresses`(목록·추가/수정 폼·기본설정·삭제) + Header "배송지" 링크. 결정(사용자): 범위=BE+독립 FE 페이지, 기본설정=전용 엔드포인트. 테스트 +14→**187**. **정적+MySQL 런타임 PASS**(V16+validate=bit↔boolean 일치, 자동기본/set-default/기본삭제 승격/IDOR 403/검증 400). ⚠️우편번호 수동입력(다음 API 미연동). **커밋 `54240aa`**.
- **Phase 1 #4 Step 3 — 주문서(배송지 스냅샷) → Phase 1 완성** — cart→pay 사이에 주문서(`/checkout`) 삽입 + 체크아웃 시 주소록에서 고른 배송지를 주문에 **스냅샷**. `ShippingInfo` @Embeddable→`Order` @Embedded, `CheckoutRequest`(addressId+memo, 서버-장바구니-진실 유지), 주문→주소는 `AddressService`+DTO로(경계), `OrderResponse.shipping`, Flyway **V17**(orders 배송 컬럼). FE `/checkout`(요약+배송지 선택[기본 자동]+메모) + cart 라우팅 변경 + 주문상세/결제화면 배송지 표시. 결정(사용자): addressId 기반·배송메모 포함·주문엔 값 스냅샷. 테스트 +1→**188**. **정적+MySQL 런타임 PASS**(V17+validate, 체크아웃→배송지 스냅샷·GET 영속·addressId 누락 400·타인 주소 403). **커밋 `674f04a`**. → **🎉 Phase 1(이미지·리뷰·필터·체크아웃) 완성.**

**10일차 (06-11)**
- **Phase 1 #1 상품 이미지** — 제품 기획 Phase 1 착수. 백엔드 `Product.imageUrl`(대표 1장·갤러리는 후속) + Flyway **V14** + DTO/서비스/테스트 동기화, FE `ProductThumb`를 실제 `<img>`로(없으면 그라데이션 폴백) + **로컬 SVG 의류 일러스트 12종**(`public/products/`, imageUrl 없으면 상품명 키워드→id 순으로 결정적 매핑). 결정: 단일 대표 imageUrl·로컬 정적(실사진 불가→손수 SVG)·nullable+FE폴백(시드 불필요). **정적 검증 PASS**(테스트·tsc·lint) + **MySQL 런타임 검증 PASS**(Claude 직접: Flyway V14 적용·validate, imageUrl 유/무 HTTP 왕복, 캐노니컬 복원). ⚠️처음 "런타임 불가" 오판=셸 cwd 지속+Glob gitignore(오답노트 기록).

**9일차 (06-10)**
- **FE 다중 PG 노출 + 관리자 UX 정리** — 백엔드 다중 PG(MPG-1~3)를 화면으로. 결제 화면 **PG 선택**(토스/카카오페이→`provider` 전송), 어드민 정산 **PG·요율 컬럼 + PG별 분해**, 어드민 대사 **PG 컬럼 + PG 필터 + PG별 분해**, `lib/provider.ts`·`types.ts` 보강. UX: **관리자 로그인 시 정산 직행**(`login()`이 User 반환)·어드민 콘솔 **"스토어로" 링크 제거**. `tsc`/`next lint` 클린, 브라우저 E2E 확인. (FE는 테스트 없이 타입검사+브라우저 검증 — 기존 관례)
- **다중 PG 비용기반 라우팅** — `provider="AUTO"`면 **가장 싼 PG 자동 선택**, 페일오버도 **비용 오름차순**(싼 PG부터). **요율 출처 단일화**: `SettlementPolicy` 요율 Map 제거 → **`PaymentGateway.feeRate()`가 단일 출처**(요율=PG 고유 속성), 라우터 `feeRateOf()`로 노출, **정산이 라우터에서 요율을 읽음**(settlement→payment 정방향) → 라우팅 비용·정산 수수료 정의가 한 곳. FE 결제화면에 "자동(최저 수수료)" 옵션. (152 tests) **MySQL 런타임 검증 PASS**(AUTO→TOSS 최저가·정산 fee=780 무결성). **다중 PG 라우팅 3전략(클라이언트 선택/페일오버/비용기반) 완성.**
- **FE 웜 부티크 디자인 착수(WIP) + 제품 기획** — 기능 위주 최소 스타일 → **웜 부티크 디자인 시스템**(크림/점토/세이지 토큰·나눔명조/Pretendard·Button/Badge/ProductThumb·스토어프론트 전 화면 리디자인). `tsc`/`lint` 클린, **`feature/fe-design-polish`(`e16d10d`) 커밋·미머지**(디자인 더 다듬을 예정). + 이커머스 **deep-research 벤치마크**(패션 셀렉트샵) → **제품 기획 `docs/product-plan.md`**: 컨셉=*입점 브랜드 셀렉트샵(운영·정산 깊이 차별화)*, 다음=**Phase 1(상품 이미지→리뷰·평점→패션 필터 UI)**. ⚠️ 리서치 표본 무신사 편중·디자인 정량근거 미검증.

---

## 🧭 핵심 결정·이정표 (요약)

면접에서 자주 꺼낼 의사결정 모음. 상세 근거는 각 월 파일 + `docs/architecture.md`.

- **Boot 3.5 고정 (4.0 회피)** — 자료 풍부·안정. 마이그레이션은 단계 경유.
- **도메인형 패키지 + 애그리거트 간 ID 참조(DDD)** — 결합도↓·경계 명확·MSA 용이. 객체연관은 애그리거트 내부만.
- **재고 동시성 = `@Version` 낙관적 락 + 재시도** — 초과판매 0을 동시성 테스트로 증명. 재시도는 트랜잭션 바깥에서 새 트랜잭션.
- **동적 검색 = QueryDSL** — 타입 안전·.NET(LINQ) 전이. Specification 대비 가독성.
- **상품 옵션(사이즈)=SKU** — 재고/`@Version`을 옵션 단위로. 색상은 별도 상품(패션 셀렉트샵 모델).
- **인증 = JWT(httpOnly 쿠키)** — access/refresh 회전+`jti`, XSS(httpOnly)·CSRF(SameSite) 트레이드오프, 401/403 구분.
- **운영 하드닝 = 시크릿 OS env(12-factor) + Flyway** — `ddl-auto: validate`로 스키마는 마이그레이션이 통제.
- **모노레포 + FE React/TS/Next.js** — 풀스택 한 레포, C#→TS 시너지.
- **🚨 보안 사고 교훈** — 로컬 DB라도 외부 노출+약한 비번이면 자동 공격 대상 → `127.0.0.1` 바인딩.

---

## 다음 작업 (예정)

- **다중 PG 완주(06-08~10, dev 병합·런타임 검증)**: MPG-1(라우터)→MPG-3(정산 PG별 수수료율)→MPG-2(대사 PG별 분류)→MPG-stretch(라우터 페일오버)→**FE 노출**(결제 PG 선택·어드민 정산/대사 PG·요율 컬럼·관리자 UX) ✅(149 tests).
- **결제 심화 한 줄 완성**: 정산 기록(매출≠결제액)→대사(5분류)→불일치 해소(예외 큐)→운영 화면(어드민 콘솔)→결제완료 이벤트(트랜잭셔널 아웃박스)→폴러 신뢰성(백오프·SKIP LOCKED)→다중 PG(라우터→PG별 수수료율→PG별 대사→페일오버→화면).
- **제품 기획 Phase 1 진행(06-11~12)**: #1 상품 이미지 ✅`b830277`. #2 리뷰·평점(백엔드 `94b6304` + FE/수정 `441c12e`) ✅ push. #3 패션 필터/검색/정렬 UI + PLP 재설계 ✅ **커밋 `b71ded8`**. #4 체크아웃 완성 = **단계별(①수량 변경→②배송지/주소록→③주문서) ✅ 전부 완료**: 수량 `4d4c41e` / 주소록 `54240aa` / **주문서(배송지 스냅샷·V17·`/checkout`) `674f04a`** (188 tests, 정적+런타임 PASS). **🎉 Phase 1(이미지·리뷰·필터·체크아웃) 완성.** → **(06-13) feature/fe-design-polish→dev `--no-ff` 머지 `74651e4`로 Phase 1 마일스톤 고정**(188 tests 재확인·push). 다음 = **dev→main 승격(PR·릴리스 마일스톤)** · Phase 2(셀러별 정산·쿠폰) · 우편번호 검색 API · 대표 이미지 갤러리.
- **제품 기획 Phase 2 진행(06-13~)**: 셀러별 정산을 차별화 하이라이트로 착수(`feature/seller-settlement`). **Step 1a ✅** = 새 `seller` 도메인(seller 1:N brand·풍부 필드·정지/재개) + `Brand.sellerId` 매핑 + Flyway V18/V19 (212 tests). **Step 1b ✅** = `OrderItem`에 brandId·sellerId 주문 시점 스냅샷 + Flyway V20 (214 tests, 이력안전 시연 PASS). **Step 2 ✅ (핵심)** = 결제를 (결제×셀러)로 분해(sellerId별 gross·PG수수료 안분·플랫폼수수료·net) + SettlementEntry sellerId/platformFee/platformFeeRate·UNIQUE 복합 + **대사 group-by-sum 재작성** + bySeller, Flyway V21 (216 tests, 멀티셀러 E2E+대사 MATCHED PASS). **Step 3 ✅** = 셀러 정산서 조회(QueryDSL 셀러·상태·기간 필터 `search` + 셀러별 집계 `summarizeBySeller`, `GET /api/settlements/summary`) + 어드민 FE 셀러별 정산 화면 강화(마이그레이션 0, 222 tests, FE tsc/lint 0; **MySQL 런타임 PASS**=summary 셀러별 집계·필터 확인, 브라우저 화면만 사용자 몫). 1a·1b·2·3 모두 MySQL 런타임 PASS. 결정 = 새 seller 도메인·결제를 셀러별 분할·PG수수료 안분+플랫폼 수수료·ADMIN 시작. **🎉 "매출≠셀러 실수령" 1급 모델링 = Phase 2 셀러별 정산(조회까지) 완성.** **(마일스톤) feature/seller-settlement→dev 병합 완료 `902509b`**(222 tests·push). **후속 ✅(`feature/seller-console`)**: ①셀러 로그인 콘솔(Role.SELLER·Member.sellerId·/api/seller/me/**·전용 /seller, V22, 235 tests) ②payout 지급 단위(Payout 묶음·V23, 247 tests) ③부분환불 안분(항목 취소+역분개 상계·V24, 250 tests). 셋 다 정적+MySQL 런타임 PASS. **(06-15) feature/seller-console→dev `--no-ff` 머지 `f249628`로 마일스톤 고정**(250 tests·push·merged 브랜치 삭제). **🎉 Phase 2 셀러별 정산 코어+후속 전체 완성**(V18~V24). 다음 = **dev→main 승격은 사용자 수동** · 새 Phase/기능.
- **쿠폰/프로모션 진행(06-15~)**: Phase 2 후속 차별화로 착수. `feature/coupon` 브랜치. **Step 1 ✅(코어+체크아웃 적용)** = `coupon` 도메인(정액/정률·플랫폼/셀러 분담·플랫폼와이드/셀러한정)·체크아웃 코드 적용·gross 보존+payable·Flyway V25(커밋 `c2ec6af`). **Step 2 ✅(정산 분담)** = 할인 안분(플랫폼와이드 비례/셀러한정 전액)·net 분담(PLATFORM부담 환원=셀러 무손실/SELLER부담=셀러 부담)·grossAmount=할인 후 몫→대사 그대로 MATCHED·Flyway V26·**276 tests**. **Step 4 ✅(FE + 미리보기)** = 체크아웃 코드입력·주문전 할인 미리보기(`POST /api/orders/coupon-preview` 읽기전용)·결제/주문상세 결제액 분해·어드민 `/admin/coupons`·정산 화면 할인 컬럼·FE types/lib/coupon.ts·**278 tests**·tsc/lint 0. **Step 2b ✅(부분환불×할인 일관성)** = 항목별 할인 안분(`Order.discountShares`)·항목 실효가=환불·정산 단일출처(과다환불 해결)·run 항목별 재설계·reverseRefunds 할인 음수 상계·마이그레이션0·**283 tests**. 넷 다 정적+런타임(스모크/MySQL) PASS. **(마일스톤) feature/coupon → dev `--no-ff` 머지 `0068920`**(283 tests·push·merged 브랜치 삭제). **Step 3 ✅(회원 쿠폰함+하이브리드)** = `Coupon.issueType`(PUBLIC 공개/ISSUED 발급)·`MemberCoupon`(발급·지갑·단일사용)·`MemberCouponService`(발급/지갑/apply·release)·취소 시 복원·체크아웃 지갑 드롭다운·Flyway V27·**291 tests**·**(마일스톤) feature/coupon-wallet → dev `--no-ff` `18203ca`**(push `f330176..18203ca`·브랜치 삭제). **🎉 쿠폰/프로모션 완성**(Step 1·2·2b·4 + Step 3, Flyway V25~V27). 다음 = **dev→main 승격은 사용자 수동** · 다른 Phase/기능.
- (다음 후보) 아웃박스 P2b(실제 RabbitMQ) / 대사 일자별 윈도우 / FE 디자인 폴리시 / 대표 이미지 갤러리(ProductImage) / 옵션 추가·수정 API / 카테고리 계층화 / dev→main 승격(마일스톤).
