# deploy/ — 경로 A(Oracle VM) 배포 산출물

경로 A(Vercel FE + Oracle Always Free VM에 Spring Boot+MySQL) 실배포에 쓰는 인프라 파일 모음.
전체 배경·무료 티어·경로 비교는 [`docs/deploy.md`](../docs/deploy.md) 참고.

| 파일 | 용도 |
|---|---|
| [`vm-setup.sh`](vm-setup.sh) | Ubuntu VM 부트스트랩 — Docker/Compose 설치·방화벽·레포 clone·`.env.prod` 준비까지 자동. 끝에 남은 수동 단계 출력. |
| [`Caddyfile`](Caddyfile) | Caddy 리버스 프록시 템플릿 — 백엔드(127.0.0.1:8080)에 HTTPS(Let's Encrypt 자동). 도메인만 교체. |

관련(백엔드 쪽):
- [`backend/docker-compose.prod.yml`](../backend/docker-compose.prod.yml) — 앱+MySQL 컨테이너(VM용).
- [`backend/.env.prod.example`](../backend/.env.prod.example) — 프로덕션 env 템플릿(→ `.env.prod`로 복사·시크릿 채움, git 제외).

## 순서 요약

```
VM 생성(Oracle 콘솔) → SSH → bash vm-setup.sh → 재로그인
 → backend/.env.prod 값 채우기
 → docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build   (Flyway 자동 적용)
 → Caddy 설치 + Caddyfile 도메인 교체 → HTTPS
 → Vercel에 FE 배포(NEXT_PUBLIC_API_BASE_URL = 백엔드 https)
 → APP_CORS_ALLOWED_ORIGINS / APP_OAUTH2_REDIRECT 를 실제 FE 도메인으로 갱신 후 재기동
 → 로그인 확인
```

> 쿠키 전략(추천): FE(Vercel)에서 `/api/*` 를 백엔드 도메인으로 rewrite → 브라우저엔 same-origin →
> 로그인 쿠키가 first-party가 되어 크로스도메인 쿠키 함정이 사라진다. (`docs/deploy.md` §6)
