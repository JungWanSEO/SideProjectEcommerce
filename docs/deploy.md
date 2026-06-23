# 배포 가이드 ($0 라이브 데모)

> 목표: 포트폴리오용 **클릭되는 데모 URL** 확보. 로컬 전용이던 앱을 무료 티어로 인터넷에 올린다.
> 이 문서는 "준비(코드/설정)"는 이미 끝난 상태를 전제로, **계정·플랫폼 단계**를 안내한다.
> 준비분: 백엔드 `Dockerfile`, 포트·CORS·쿠키·DB주소 **환경변수화**(아래 env 표), FE `NEXT_PUBLIC_API_BASE_URL`.

---

## 1. 올릴 3덩어리

```
[프론트 Next.js]  ──fetch(credentials)──▶  [백엔드 Spring Boot]  ──JDBC──▶  [MySQL]
     Vercel(무료)                              Render/Koyeb(무료)            무료 MySQL 호스트
```

- **RabbitMQ는 prod에서 불필요** — `outbox.publisher` 기본값이 `in-process`라 브로커 없이 정상 동작(opt-in). 배포 MVP는 위 3개만.
- **DB는 MySQL 유지** — Flyway 스크립트가 MySQL 전용(enum·AUTO_INCREMENT 등)이라, 무료 **MySQL 호스트**를 쓰면 마이그레이션을 안 갈아엎어도 된다.

## 2. 무료 플랫폼 (티어는 바뀌니 가입 시 재확인)

| 덩어리 | 추천(무료) | 캐치 |
|---|---|---|
| 프론트 | **Vercel** (Hobby) | Next.js 거의 자동. `NEXT_PUBLIC_*`는 **빌드 타임 주입** → 값 바꾸면 재배포 필요. |
| 백엔드 | **Render** Web Service (Free) 또는 **Koyeb** Free | Render 무료는 **15분 무활동 시 슬립** → 첫 요청 콜드스타트 ~30–50s. Dockerfile로 빌드. |
| MySQL | **TiDB Cloud Serverless**(MySQL 호환·무료) 또는 **Aiven**/**Clever Cloud** 무료 | 무료 MySQL은 용량·연결수 제한. TiDB는 MySQL 프로토콜이라 대체로 호환(드물게 DDL 차이). |

> 진짜 $0가 빡세면 대안: **Render 무료 Postgres**로 가되 Flyway를 Postgres로 포팅(품 듦). 이 프로젝트는 **MySQL 유지**를 권장.

## 3. 환경변수 (이게 배포의 핵심)

준비 단계에서 코드의 하드코딩(localhost·포트·origin·쿠키)을 전부 env로 뺐다. 플랫폼 대시보드에 아래를 넣는다.

### 백엔드 (Render/Koyeb)
| 변수 | 값 | 설명 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://<host>:<port>/<db>?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` | MySQL 호스트가 준 주소 |
| `SPRING_DATASOURCE_USERNAME` | (DB 유저) | |
| `SPRING_DATASOURCE_PASSWORD` | (DB 비번) | |
| `JWT_SECRET` | (긴 랜덤 문자열, 32바이트+) | 토큰 서명 키 — 새로 생성 |
| `APP_CORS_ALLOWED_ORIGINS` | `https://<your-app>.vercel.app` | FE 도메인 허용(콤마로 여러 개) |
| `APP_COOKIE_SECURE` | `true` | https 전용 쿠키 |
| `APP_COOKIE_SAME_SITE` | `None` | FE/BE 도메인이 달라 **크로스사이트** → None 필수(Secure와 한 쌍) |
| `SPRING_PROFILES_ACTIVE` | `dev` *(선택)* | 데모 데이터 시드(빈 사이트 대신 카탈로그·주문 보이게). 빈 데모 원하면 생략 |
| `PORT` | *(플랫폼 자동 주입)* | `server.port=${PORT:8080}`가 받음 — 직접 설정 불필요 |

### 프론트 (Vercel)
| 변수 | 값 |
|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `https://<your-backend>.onrender.com` (백엔드 URL) |

## 4. ⚠️ 크로스도메인 로그인 함정 (제일 흔한 실패)

FE(vercel.app)와 BE(onrender.com)가 **다른 도메인**이라, httpOnly 쿠키 로그인이 까다롭다. 준비 단계에서 이렇게 풀어 뒀다:
- **CORS**: `APP_CORS_ALLOWED_ORIGINS`에 FE 도메인 명시 + `allowCredentials=true`(코드). 와일드카드 `*`는 쿠키와 못 씀 → 정확한 origin을 넣을 것.
- **쿠키**: `APP_COOKIE_SECURE=true` + `APP_COOKIE_SAME_SITE=None` → 크로스사이트 요청에도 쿠키가 실린다(둘은 한 쌍, None은 Secure 필수).
- FE는 이미 `credentials:"include"`로 호출(코드).
- 그래도 안 되면: 브라우저의 **서드파티 쿠키 차단**이 원인일 수 있다. 그땐 FE·BE를 **같은 상위 도메인의 서브도메인**(api.example.com ↔ app.example.com)으로 묶거나, BE 앞에 리버스 프록시를 둔다(후속).

## 5. 단계 (계정·클릭은 사용자)

1. **MySQL 호스트** 가입 → DB 생성 → 접속 URL/유저/비번 확보.
2. **백엔드 배포**(Render 예): New → Web Service → 이 GitHub 레포 연결 → Root Directory `backend` → Dockerfile 자동 감지 → 위 백엔드 env 입력 → Deploy.
   - 첫 기동 시 **Flyway가 마이그레이션을 적용**(V1~최신)해 스키마를 만든다. `SPRING_PROFILES_ACTIVE=dev`면 데모 데이터도 시드.
   - 헬스체크 경로: `/actuator/health`.
3. **프론트 배포**(Vercel): New Project → 레포 연결 → Root Directory `frontend` → env `NEXT_PUBLIC_API_BASE_URL`=백엔드 URL → Deploy.
4. **연결 마무리**: 백엔드 `APP_CORS_ALLOWED_ORIGINS`를 **실제 Vercel 도메인**으로 갱신 → 백엔드 재배포. 로그인 테스트.

## 6. 메모

- **콜드스타트**: Render 무료는 슬립 → 데모 첫 클릭이 느릴 수 있음(정상). 면접 직전이면 미리 한 번 깨워두기.
- **시크릿**: `.env`·키는 깃에 없음(`.gitignore`). 플랫폼 대시보드에만 넣는다.
- **CI**: `.github/workflows/ci.yml`가 push마다 테스트 — 배포 전 회귀 방지.
- **로컬은 그대로**: 위 env는 전부 로컬 기본값(localhost·8080·Lax·false)이 있어 `./run.ps1`/`npm run dev`는 변화 없음.
- **후속(여유 시)**: 커스텀 도메인+HTTPS · 서드파티 쿠키 회피용 동일도메인 구성 · RabbitMQ를 prod에 올리려면 매니지드(CloudAMQP 무료) + `OUTBOX_PUBLISHER=rabbit`.
