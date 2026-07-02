# 배포 가이드 ($0 라이브 데모) — 2026.6 검증

> 목표: 포트폴리오용 **클릭되는 데모 URL** 확보. 로컬 전용 앱을 무료 티어로 인터넷에 올린다.
> "준비(코드/설정)"는 이미 끝난 상태 전제 — 백엔드 `Dockerfile`, 포트·CORS·쿠키·DB주소 **환경변수화**(아래 env 표), FE `NEXT_PUBLIC_API_BASE_URL`.
> ⚠️ **무료 티어는 자주 바뀐다.** 아래는 2026년 6월 공식 문서로 검증한 스냅샷 — **가입 직전 콘솔에서 재확인**할 것.

---

## 1. 올릴 것 + 두 경로

```
[Next.js FE]  ──fetch(쿠키)──▶  [Spring Boot BE(Docker)]  ──JDBC──▶  [MySQL 8]
```
- **RabbitMQ는 prod 불필요** — `outbox.publisher=in-process` 기본이라 브로커 없이 정상(opt-in). MVP는 위 3개.
- **DB는 MySQL 유지** — Flyway 스크립트가 MySQL 전용(enum·AUTO_INCREMENT)이라 **진짜 MySQL**을 써야 마이그레이션을 안 갈아엎는다. (Postgres·MySQL"호환"이 아니라 MySQL 8.)

**두 가지 $0 경로** (§3·§4에서 상술):
- **경로 A (추천 · 항상 켜짐)**: Vercel(FE) + **Oracle Cloud Always Free VM 한 대**에 Spring Boot + MySQL 8(+필요시 Redis·RabbitMQ). 콜드스타트 없음, 진짜 MySQL, 쿠키 문제 회피 가능. 대신 Linux 셋업 품.
- **경로 B (셋업 쉬움 · 콜드스타트 감수)**: Vercel(FE) + Render/Koyeb(BE) + **Aiven Free MySQL**(DB). 클릭 배포는 쉽지만 무료 BE가 유휴 시 잠들어 첫 요청이 느림.

## 2. 무료 플랫폼 검증 (2026.6 · 공식 문서 기준)

| 덩어리 | 플랫폼 | $0? | 한도 / 함정 |
|---|---|:--:|---|
| FE | **Vercel Hobby** | ✅ | 비영리 개인만(↓아래 주의). 4 CPU-hrs·360 GB-hrs·1M 호출·100배포/일 |
| BE+DB 한 박스 | **Oracle Always Free ARM** | ✅ 영구 | **2 OCPU/12GB**(2026.6 4/24→2/12 감축)·항상 켜짐·MySQL 직접 설치 |
| BE 관리형 | Render Free Web Service | ✅ | 750h/월·**15분 무활동→슬립·~1분 콜드스타트**·소진 시 월말까지 중지 |
| BE 관리형 | Koyeb Free Instance | ✅ | 512MB/0.1vCPU·org당 1개·FRA/DC만·**1시간→슬립**(512MB는 Spring Boot에 빠듯) |
| DB 관리형 | **Aiven Free MySQL** | ✅ 영구 | **진짜 MySQL**(Flyway 안전)·1CPU/1GB/**디스크 1GB**(5→1GB 감축)·유휴 시 슬립 |
| DB 관리형 | Oracle MySQL HeatWave | ✅ 영구 | 50GB·**진짜 MySQL**(Oracle Always Free에 포함) |
| DB 관리형 | TiDB Cloud Starter | ✅ | 5GiB·무카드 · ⚠️**MySQL 8 아님(와이어 호환)→Flyway 검증 필수** |
| DB 관리형 | Filess.io | ✅ | ⚠️**동시연결 5개**→HikariCP 기본 10 초과(`maximum-pool-size`≤4로 줄여야) |
| ~~BE/DB~~ | ~~Railway~~ | ❌ | **무료 아님**(트라이얼 크레딧만, 이후 유료) |
| ~~DB~~ | ~~PlanetScale~~ | ❌ | 2024.4 무료 폐지 |

> ⚠️ **Vercel Hobby 비영리 제한**: "상업적 사용"(실결제·광고·유료 대행)은 Pro 필요. **하지만** 우리처럼 **실결제 없는 커머스 "클론" 데모는 합법** — Vercel 직원이 공식 포럼에서 "돈을 벌지 않는 데모 사이트는 괜찮다"고 명시(체크아웃 UI가 있어도 모의 PG라 OK).

## 3. 경로 A — Oracle Always Free VM (진짜 $0 · 항상 켜짐) ★추천

VM 한 대에 Docker로 BE+MySQL을 올린다. **이 스택에 가장 잘 맞는 이유**:
- **콜드스타트 없음** — 면접 중 첫 클릭이 안 느리다(관리형 무료는 다 잠든다).
- **진짜 MySQL 8** — Flyway MySQL 전용 마이그레이션이 그대로 돈다.
- **쿠키 문제 회피** — FE/BE를 같은 도메인 서브도메인(app./api.)으로 묶으면 크로스사이트 쿠키 이슈(§6)가 사라진다.
- 여유가 있으면 Redis(레이트리밋·캐시)·RabbitMQ(아웃박스)까지 같은 박스에 opt-in으로 켤 수 있다.

**사양/함정**:
- 2 OCPU / 12 GB RAM (Ampere A1, 영구 무료·계정 수명) — ⚠️ 2026.6 중순 4/24→2/12로 감축됨(2차 출처). **콘솔에서 현재값 재확인.**
- 가입 시 **신용카드 등록**(과금 안 됨) · 부트볼륨 47GB+(200GB 한도 내).
- ⚠️ **유휴 회수**: 7일간 95%ile CPU·네트워크·메모리가 모두 <20%면 인스턴스 회수될 수 있음 → **UptimeRobot 등으로 `/actuator/health`를 주기 핑**해 깨어있게.
- ⚠️ **ARM 용량 부족**("out of host capacity")으로 생성이 막힐 수 있음 → 덜 붐비는 홈 리전 선택·재시도.

**셋업 개요** (사용자가 직접):
1. OCI 가입 → Always Free Ampere A1 VM(Ubuntu) 생성, 22/80/443 포트 보안목록 오픈.
2. VM에 Docker + Compose 설치 → 이 레포 clone → `backend/docker-compose.yml`로 MySQL + (이미지 빌드한) 앱 기동. env는 §5.
3. **Caddy 또는 nginx** 리버스 프록시로 HTTPS(Let's Encrypt 자동) + 도메인 연결. 도메인 무료가 필요하면 DuckDNS 등.
4. FE는 Vercel(§4와 동일), `NEXT_PUBLIC_API_BASE_URL`=백엔드 https 주소.

## 4. 경로 B — 관리형 분리 (셋업 쉬움 · 콜드스타트)

- **FE**: Vercel. **BE**: Render Free Web Service(레포 연결·Dockerfile 자동·15분 슬립) 또는 Koyeb Free. **DB**: **Aiven Free MySQL**(진짜 MySQL→Flyway 그대로). *TiDB는 MySQL 8이 아니라 마이그레이션 호환을 먼저 테스트해야 함.*
- 장점: 대시보드 클릭으로 배포, Linux 셋업 0.
- 단점: BE가 유휴 시 잠들어 **첫 요청 ~1분 콜드스타트**(면접 직전 미리 깨워두기), Aiven 디스크 1GB(데모 시드엔 충분), **크로스도메인 쿠키 설정 필요**(§6).

## 5. 환경변수 (두 경로 공통 — 플랫폼/VM에 주입)

### 백엔드
| 변수 | 값 | 설명 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://<host>:<port>/<db>?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` | MySQL 주소(경로 A=VM 내부 mysql, B=Aiven 주소) |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | (DB 유저/비번) | |
| `JWT_SECRET` | (긴 랜덤 32바이트+) | 토큰 서명 키 — 새로 생성 |
| `APP_CORS_ALLOWED_ORIGINS` | `https://<your-app>.vercel.app` | FE 도메인 허용(콤마로 여러 개) |
| `APP_COOKIE_SECURE` | `true` | https 전용 쿠키 |
| `APP_COOKIE_SAME_SITE` | `None`(경로 B) / `Lax`(경로 A·같은 상위도메인) | 크로스사이트면 None+Secure 한 쌍 |
| `APP_OAUTH2_REDIRECT` | `https://<your-app>.vercel.app` | **소셜 로그인 켤 때 필수** — 로그인 성공 후 돌아갈 FE 주소. 안 넣으면 `localhost:3000`으로 리다이렉트돼 깨짐 |
| `SPRING_PROFILES_ACTIVE` | `dev` *(선택)* | 데모 데이터 시드(빈 사이트 방지) |
| `PORT` | *(플랫폼 자동)* | `server.port=${PORT:8080}`가 받음 |

### 프론트 (Vercel)
| 변수 | 값 |
|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `https://<백엔드 주소>` (⚠️ 빌드타임 주입 → 바꾸면 재배포) |

## 6. ⚠️ 크로스도메인 로그인 함정 (경로 B의 제일 흔한 실패)

FE(vercel.app)와 BE(다른 도메인)가 다르면 httpOnly 쿠키 로그인이 까다롭다. 준비 단계에서 풀어 둔 것:
- **CORS**: `APP_CORS_ALLOWED_ORIGINS`에 FE 도메인 명시 + `allowCredentials=true`(코드). 와일드카드 `*`는 쿠키와 못 씀.
- **쿠키**: `APP_COOKIE_SECURE=true` + `APP_COOKIE_SAME_SITE=None`(둘은 한 쌍).
- FE는 이미 `credentials:"include"`(코드).
- 그래도 막히면(브라우저 서드파티 쿠키 차단): **경로 A처럼 FE·BE를 같은 상위도메인 서브도메인**(api.example.com ↔ app.example.com)으로 묶거나 Vercel rewrites로 `/api/*`를 프록시 → 쿠키가 first-party가 되어 문제 소멸. **경로 A가 운영상 더 단순한 이유.**

## 7. 단계 (계정·클릭은 사용자)

**공통**: MySQL 확보(경로 A=VM에 설치 / B=Aiven 가입·DB 생성) → 접속 URL/유저/비번.
- **경로 A**: §3 셋업 개요대로 VM에 BE+MySQL 기동, Caddy로 HTTPS. 첫 기동 시 **Flyway가 V1~최신 적용**.
- **경로 B**: Render New → Web Service → 레포 연결 → Root `backend` → Dockerfile 감지 → §5 env → Deploy. 헬스체크 `/actuator/health`.
- **FE(공통)**: Vercel New Project → 레포 → Root `frontend` → `NEXT_PUBLIC_API_BASE_URL` → Deploy.
- **마무리**: 백엔드 `APP_CORS_ALLOWED_ORIGINS`를 실제 Vercel 도메인으로 갱신 → 재배포 → 로그인 테스트.

## 8. 메모

- **시크릿**: `.env`·키는 깃에 없음(`.gitignore`). 플랫폼/VM에만 넣는다.
- **CI**: `.github/workflows/ci.yml`가 push마다 테스트 — 배포 전 회귀 방지.
- **로컬 무변화**: 위 env는 전부 로컬 기본값(localhost·8080·Lax·false)이 있어 `./run.ps1`/`npm run dev`는 그대로.
- **신선도**: 무료 티어는 자주 바뀐다. 특히 **Oracle ARM 무료가 2026.6 감축**됐고 Aiven 디스크도 5→1GB로 줄었다 — 가입 직전 공식 콘솔 재확인.
- **DB 선택 요지**: Flyway가 MySQL 전용이라 **진짜 MySQL**(VM 자체 설치 · Aiven · Oracle MySQL HeatWave)이 안전. TiDB(와이어 호환)는 쓰려면 마이그레이션을 먼저 테스트.
- **후속(여유 시)**: 커스텀 도메인 · RabbitMQ를 prod에 올리려면 매니지드(CloudAMQP 무료) + `OUTBOX_PUBLISHER=rabbit`.
