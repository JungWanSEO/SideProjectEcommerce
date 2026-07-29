# 테스트 커버리지 (JaCoCo)

> **2026-07-29 기준: instruction 91.3% · branch 81.0% · line 92.0% · class 94.8% (724 tests)**
> 이력: 82.6%(07-14 도입) → 84.5%(0% 구멍 메움) → 86.6%(분기 보강, 542 tests) → **91.3%(커머스 심화 #1~#9·알림·데모 시드까지 테스트와 함께 쌓임, 724 tests)**.
> 커버리지를 목표로 잡고 올린 게 아니라, **기능마다 동시성·돈 경로 테스트를 붙인 결과**로 따라 올라온 수치다.
>
> 목적: 테스트가 **어디를 덮는지**가 아니라 **어디를 안 덮는지**를 본다.
> 특히 정산·환불·쿠폰처럼 **돈이 걸린 경로**의 빈 구멍이 진짜 위험이다.

## 실행

```bash
cd backend
./gradlew test           # test가 jacocoTestReport를 finalizedBy로 물고 있어 리포트까지 함께 생성
# 열기: backend/build/reports/jacoco/test/html/index.html
```

CI(GitHub Actions)는 매 push/PR마다 리포트를 만들어 **`jacoco-coverage` 아티팩트**로 올린다(14일 보관).

## 측정 대상에서 뺀 것 (숫자가 의미를 갖게)

| 제외 | 이유 |
|---|---|
| `**/Q*.class` | QueryDSL이 생성한 Q클래스 — 우리가 쓴 코드가 아니다 |
| `**/dto/**` | record DTO — 로직 없는 데이터 홀더(생성자만 세도 숫자만 부풀린다) |
| `**/global/config/**` | 설정 클래스(빈 배선) |
| `CommerceApiApplication` | 부트 진입점 |

## 현재 (2026-07-14 · 496 tests · 구멍 메운 뒤)

- **명령어(instruction) 84.5%** · **분기(branch) 71.9%**

| 0%였던 클래스 | 지금 | 무엇으로 |
|---|---|---|
| `AuditLogRepositoryImpl` | **98.7%** | `AuditLogRepositoryTest`(@DataJpaTest — 행위자·액션·대상·결과·기간 윈도우·최신순) |
| `SettlementController` | **100%** | `SettlementControllerTest`(@WebMvcTest — 배치/목록/역분개/셀러집계/입금·409) |
| `ReconciliationController` | **93.3%** | `ReconciliationControllerTest`(대사 윈도우 바인딩·불일치 목록·해소/무시·409) |
| `MemberCouponClaimService` | **100%** | `MemberCouponClaimServiceTest`(락 키가 쿠폰별인지 — 전역 키면 처리량 붕괴) |

---

## 기준선 (2026-07-14 · 477 tests · 도입 시점)

- **명령어(instruction) 82.6%** (12,364 / 14,963)
- **분기(branch) 70.1%** (585 / 834)

### 🔴 0% — 테스트가 한 번도 실행하지 않은 클래스 (아래 4개는 위 표대로 해소됨)

| 클래스 | 왜 위험한가 |
|---|---|
| `audit/repository/AuditLogRepositoryImpl` | **감사 로그 검색의 QueryDSL 동적 where 전부**. 필터가 틀려도 아무도 모른다 |
| `coupon/service/MemberCouponClaimService` | 선착순 쿠폰 claim의 **락 + 레이트리밋 진입점**(동시성 테스트는 안쪽 `MemberCouponService`만 친다) |
| `settlement/controller/SettlementController` | 정산 배치 실행·지급 처리 **HTTP 경계**(서비스는 91.9% 덮였는데 컨트롤러는 0%) |
| `settlement/controller/ReconciliationController` | 대사 실행·불일치 해소 HTTP 경계 |
| `coupon/controller/MemberCouponController` | 쿠폰 발급/지갑 HTTP 경계 |
| `activity`·`recommendation`·`wishlist` 컨트롤러 | 얇은 위임이지만 인가·바인딩 회귀를 못 잡는다 |
| `global/init/DemoDataSeeder` | dev 프로파일 전용 시드(운영 무관) — **의도적으로 안 덮는다** |
| `global/security/oauth2/OAuth2ClientConfig` | 소셜 자격증명 있을 때만 활성(테스트 환경엔 없음) — 구조적으로 못 덮음 |

### 🟡 돈 흐름 중 낮은 쪽

| 클래스 | 커버리지 |
|---|---|
| `payment/entity/Payment` | 55.1% |
| `payment/service/PaymentService` | 64.1% |
| `order/service/OrderService` | 66.5% |
| `coupon/service/MemberCouponService` | 66.9% |

### 🟢 잘 덮인 곳

`settlement/service/SettlementService` **91.9%** — "매출≠셀러 실수령" 코어(안분·역분개)는 두껍게 덮여 있다.

## 다음

- 남은 0%: `DemoDataSeeder`(dev 시드 — **의도적 제외**)·`OAuth2ClientConfig`(자격증명 있을 때만 활성 — 구조적으로 못 덮음)·얇은 위임 컨트롤러(activity·recommendation·wishlist).
- 🟡 다음 우선순위는 **분기(branch) 71.9%** — 조건 분기(가드·예외 경로)가 라인보다 덜 덮여 있다. `PaymentService`(64.1%)·`MemberCouponService`(66.9%)의 실패/경계 경로부터.
