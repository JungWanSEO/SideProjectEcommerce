# ADR-0005: 인증 = JWT httpOnly 쿠키 (access/refresh 회전 + jti)

- **상태**: 채택(Accepted)
- **근거**: [architecture.md §6, §12](../architecture.md) · [dev-log](../dev-log.md)

## 배경 (Context)
SPA(Next.js) 프론트와 분리된 스테이트리스 API 인증이 필요했다. 토큰을 어디에 보관하느냐가 XSS·CSRF 위험을 가른다(localStorage는 JS로 읽혀 XSS에 취약). 초기엔 Authorization 헤더 Bearer였으나 브라우저 흐름에서 쿠키 기반으로 전환했다.

## 결정 (Decision)
access·refresh 토큰을 모두 **httpOnly 쿠키**(`AuthCookieManager`, `ResponseCookie` httpOnly + SameSite=Lax)에 담는다 — JS(`document.cookie`)가 못 읽어 XSS 토큰 탈취를 막고, SameSite=Lax로 타 사이트發 요청 차단(CSRF). access는 role claim·30분·무상태, refresh는 role claim 없음·14일·DB(`refresh_token`) 저장(stateful). 둘 다 **jti(UUID)** 로 같은 초 발급도 구분돼 회전·재사용 탐지가 된다. refresh 시 저장본과 정확히 일치해야 하고 새 토큰 발급과 동시에 in-place upsert(멤버당 1레코드); 불일치/이미 회전됨이면 401. principal=memberId(Long), HS256 대칭키.

## 대안 (Alternatives)
- **(A) localStorage 토큰** — XSS로 토큰 탈취 위험 → 탈락.
- **(B) 서버 세션(stateful 전체)** — 스테이트리스 확장성 포기 → 탈락.
- **(C) RS256 비대칭키** — 단일 서비스엔 과함, 다중 서비스로 커지면 채택 여지.
- **(D) JWT 단일 토큰(refresh 없음)** — 만료/탈취 대응 약함 → 탈락.

## 결과 (Consequences)
- **긍정**: XSS(httpOnly)·CSRF(SameSite) 동시 방어, 무상태 access로 확장성, refresh 회전으로 탈취 토큰 무효화·재사용 탐지. 401 EntryPoint/403 핸들러 구분, FE 자동 refresh.
- **부정**: SameSite=Lax라 FE/API 도메인이 다르면 SameSite=None+CSRF 토큰 검토 필요(TODO), https 배포 시 Secure=true 필요. refresh DB 저장으로 완전 무상태는 아님.
