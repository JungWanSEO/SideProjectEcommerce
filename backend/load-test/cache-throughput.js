// 캐시 처리량 부하 테스트 (k6) — 상품 상세를 부하로 때려 "캐시 ON vs OFF"의 처리량/지연 차이를 본다.
//
// 실행(앱이 8080에 떠 있어야 함):
//   ON  : 기본 기동(캐시 켜짐) 후
//         docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < cache-throughput.js
//   OFF : APP_CACHE_ENABLED=false 로 기동 후 같은 명령
//   두 결과의 http_reqs(RPS)·http_req_duration p(95) 를 비교한다.
//
// 왜 같은 상품 1건만? 캐시 효과를 고립시켜 보려는 의도 — hot key 하나면 캐시 ON은 첫 미스 뒤 전부 적중,
// OFF는 매번 DB 조회+enrich. 부하가 걸리면 DB가 병목이라 그 차이가 p95/RPS로 드러난다.

import http from 'k6/http';
import { check } from 'k6';

// VU 수/시간은 env로 조절(기본 20 VU·20s). Docker Desktop NAT가 초고RPS(~8600)에서 포화돼 측정이 오염되므로,
// 그 아래의 "지속 가능한 동시 부하"로 앱 자체(캐시 ON vs OFF) 차이를 본다.
const VUS = parseInt(__ENV.VUS || '20');
const DURATION = __ENV.DURATION || '20s';

export const options = {
  // constant-vus: VU가 쉬지 않고 반복 요청 → 일정한 동시 부하. ON/OFF 공정 비교엔 고정 부하가 명확.
  scenarios: {
    steady: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  // thresholds = SLO 게이트. 못 넘으면 k6가 실패(비교 외에 "기준 충족?"도 본다).
  thresholds: {
    http_req_failed: ['rate<0.01'],     // 에러율 1% 미만
    http_req_duration: ['p(95)<800'],   // p95 800ms 미만(느슨한 sanity gate — 비교가 주목적)
  },
};

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';

export default function () {
  const res = http.get(`${BASE}/api/products/${PRODUCT_ID}`);
  // check = 응답 단언(부하 중에도 200을 주는지). 실패해도 멈추진 않고 비율로 집계된다.
  check(res, { 'status is 200': (r) => r.status === 200 });
}
