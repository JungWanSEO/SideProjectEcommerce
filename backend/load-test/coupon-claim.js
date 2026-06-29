// 선착순 쿠폰 정합성+처리량 부하 테스트 (k6) — "200명이 동시에 100장 한정 쿠폰을 받으면 정확히 100장만?"
//
// 실행(앱이 8080에 떠 있어야 함, 락 모드별로 RUN 바꿔가며):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e RUN=none \
//     grafana/k6 run - < coupon-claim.js
//   (락 모드는 앱 기동 시 APP_LOCK_PROVIDER=none|redis|redisson 로 정하고, RUN도 맞춰 쿠폰 코드를 구분한다.)
//
// 무엇을 증명하나:
//  - 정합성: 실제 HTTP 동시 요청 200개여도 발급은 정확히 LIMIT(=100), 초과 0 (DB 원자 UPDATE가 보증).
//  - 처리량/지연: 락 모드(none/redis/redisson)별 claim 지연을 대조 → 락의 비용.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const MEMBERS = parseInt(__ENV.MEMBERS || '200');   // 동시 사용자 수
const LIMIT = parseInt(__ENV.LIMIT || '100');       // 쿠폰 한정 수량
const RUN = __ENV.RUN || 'run';                     // 쿠폰 코드 구분(락 모드별 재실행)
const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };

// 커스텀 카운터 — 발급 성공/마감/기타를 직접 집계(409는 k6 기본상 'failed'라 http_req_failed로는 못 본다).
const claimSuccess = new Counter('claim_success');
const claimSoldOut = new Counter('claim_soldout');
const claimOther = new Counter('claim_other');
// claim 요청만의 지연(setup의 가입/로그인 요청이 http_req_duration에 섞이는 걸 피해 따로 본다).
const claimLatency = new Trend('claim_latency', true);

export const options = {
  scenarios: {
    // per-vu-iterations: VU 200명이 각자 딱 1번 → 거의 동시에 200개 claim이 몰린다(선착순 재현).
    burst: { executor: 'per-vu-iterations', vus: MEMBERS, iterations: 1, maxDuration: '60s' },
  },
  // ★ 정합성 게이트: 성공이 LIMIT을 넘으면 실패 = "초과 발급 0" 을 자동 검증.
  thresholds: { claim_success: [`count<=${LIMIT}`] },
};

// setup(): 측정 전 1회 — 쿠폰 생성 + 회원 토큰 수집. 여기 시간은 측정 지표에 안 들어간다.
export function setup() {
  // 1) 어드민 로그인 → access_token 쿠키 추출 → 선착순 쿠폰 생성(LIMIT 장)
  const adminLogin = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ email: 'admin@commerce.com', password: 'password123' }), JSON_HDR);
  const adminToken = adminLogin.cookies['access_token'][0].value;
  const adminHdr = { headers: { 'Content-Type': 'application/json', Cookie: `access_token=${adminToken}` } };

  const couponRes = http.post(`${BASE}/api/coupons`, JSON.stringify({
    code: `LOADTEST-${RUN}-${Date.now()}`, name: '부하테스트 선착순',
    discountType: 'FIXED_AMOUNT', discountValue: 1000, maxDiscountAmount: null,
    minOrderAmount: 0, fundedBy: 'PLATFORM', issueType: 'ISSUED', sellerId: null,
    validFrom: '2026-01-01T00:00:00', validUntil: '2027-12-31T23:59:59', totalQuantity: LIMIT,
  }), adminHdr);
  const couponId = couponRes.json('data.id');

  // 2) 회원 MEMBERS명: 가입(이미 있으면 409 무시) → 로그인 → 각자 access_token 수집.
  //    회원은 재사용(이메일 고정)·쿠폰만 매 실행 새로 만든다 → (회원,쿠폰) UNIQUE 충돌 없음.
  const tokens = [];
  for (let i = 1; i <= MEMBERS; i++) {
    const email = `load-${i}@commerce.com`;
    const password = 'loadpass1234';
    http.post(`${BASE}/api/members`,
      JSON.stringify({ email, password, nickname: `load${i}` }), JSON_HDR);   // 201 또는 409 — 무시
    const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password }), JSON_HDR);
    tokens.push(login.cookies['access_token'][0].value);
  }
  return { couponId, tokens };
}

// 각 VU가 자기 회원 토큰으로 딱 1번 claim.
export default function (data) {
  const token = data.tokens[__VU - 1];   // VU i → i번째 회원(1인 1장)
  const res = http.post(`${BASE}/api/member-coupons/claim/${data.couponId}`, null,
    { headers: { Cookie: `access_token=${token}` } });

  claimLatency.add(res.timings.duration);
  if (res.status === 201) claimSuccess.add(1);
  else if (res.status === 409) claimSoldOut.add(1);
  else { claimOther.add(1); console.log(`claim status=${res.status}`); }   // 진단: 201/409 외 상태 확인

  check(res, { 'claim 201(발급) 또는 409(마감)': (r) => r.status === 201 || r.status === 409 });
}
