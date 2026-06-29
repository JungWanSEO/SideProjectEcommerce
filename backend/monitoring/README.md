# 관측성 (Prometheus + Grafana)

Micrometer 메트릭을 `/actuator/prometheus`로 노출 → Prometheus가 스크레이프 → Grafana로 시각화.
대시보드는 **"위에서 아래로 읽히는 3단 내러티브"**로 구성했다(메트릭 덤프가 아니라 이야기).

## 띄우기 (opt-in)

기본 `docker compose up -d`엔 안 뜬다. 관측성을 볼 때만:

```bash
# backend/ 에서
docker compose --profile observability up -d prometheus grafana
./run.ps1            # 앱(8080) — Prometheus가 host.docker.internal:8080 을 스크레이프
```

- **Grafana**: http://localhost:3001  (admin / admin) → 대시보드 **"commerce-api 관측성"**
- **Prometheus**: http://localhost:9090  (타깃 상태 `/targets`, 쿼리 `/graph`)
- 내리기: `docker compose --profile observability down`

## 대시보드 구성 (표현 = 3단 내러티브)

| 그룹 | 질문 | 패널 | 출처 |
|---|---|---|---|
| **① 사용자가 겪는 것** | 서비스 건강한가? | RPS · 지연 p50/p95/p99 · 에러율(4xx/5xx) · 엔드포인트별 RPS | `http_server_requests_*` |
| **② 시스템이 버티는가** | 어디가 병목인가? | JVM 힙 · **DB 풀(Hikari active/idle/pending)** · CPU · Tomcat 스레드 | `jvm_*`·`hikaricp_*`·`tomcat_*` |
| **③ 우리가 만든 것의 효과** | 기능이 일하나? | **캐시 적중률** · **선착순 claim 결과(201/409/503)** | `cache_gets_total`·`http_server_requests`(uri·status) |

> ②의 Hikari pending·③의 claim 결과는 부하테스트에서 본 병목/락 동작을 **실시간**으로 보여준다.
> 예: 선착순 부하(`load-test/coupon-claim.js`)를 redis 모드로 돌리면 ③ 패널에 503이 그려진다.

## 핵심 설정 (왜 이렇게)

- **히스토그램 버킷**: `management.metrics.distribution.percentiles-histogram[http.server.requests]=true` —
  켜야 `_bucket` 시리즈가 나와 Prometheus `histogram_quantile`로 p95/p99를 계산할 수 있다(기본은 count/sum뿐).
- **Tomcat 메트릭**: `server.tomcat.mbeanregistry.enabled=true` — 켜야 `tomcat_threads_*`가 노출된다.
- **보안**: `/actuator/prometheus`만 공개(스크레이프용)·나머지 actuator는 인증. ⚠️ 운영에선 메트릭 노출을
  막아야 한다 — **관리 포트 분리**(`management.server.port`) + 네트워크 제한이 정석.
- **앱은 호스트에서** 돌고 Prometheus는 컨테이너 → `host.docker.internal:8080`로 스크레이프(`prometheus.yml`).
