# 알림 매트릭스 — #6 알림 설계 청사진

> **목적**: 알림(#6)을 "만들 수 있어서"가 아니라 **비즈니스 근거**로 설계하기 위한 단일 기준 문서.
> 앞으로 이 기능의 Agent·Skill·엔지니어링 설계는 이 매트릭스를 출발점으로 삼는다.
> **근거 코드**: 상태머신·이벤트 발행 지점을 실제 코드에서 확인해 작성(2026-07-27). 관련=[architecture.md](architecture.md) §8(events)·backlog #6.

---

## 0. 현재 상태 (왜 "절반만 깔려 있다"인가)

파이프라인은 **인프라로는 완성**: 트랜잭셔널 아웃박스 → RabbitMQ(또는 in-process) → `EventDispatcher` → `OutboxEventHandler`, at-least-once + `notification_log.event_id` UNIQUE 멱등.

**그러나 실제로 흐르는 이벤트는 `PAYMENT_COMPLETED` 하나뿐**이고([PaymentCompletionRecorder](../backend/src/main/java/com/commerce/api/payment/service/PaymentCompletionRecorder.java)), 소비 결과인 [NotificationLog](../backend/src/main/java/com/commerce/api/notification/entity/NotificationLog.java)에는 **`recipient`도 `readAt`도 없고 조회 컨트롤러도 없다** → *"보냈다고 치는 모의 로그"*일 뿐 **아무도 못 읽는다.**

→ **#6의 본질 = 읽는 쪽(read side)을 붙여 이벤트 루프를 사용자까지 잇고, 발행 이벤트를 비즈니스 필요만큼 늘리는 것.** 도메인 상태(주문·shipment·반품·정산·재고)는 이미 다 있으므로, 대부분은 "상태 전이 지점에서 `OutboxService.append` 한 줄 + 핸들러"로 연결하는 작업이다.

## 1. 설계 원칙 (알림 판단 리트머스)

> **알림을 보낸다 = 누군가의 "해야 할 일"이 바뀌거나 "돈·신뢰"에 영향이 생겼는데, 그가 화면을 보고 있다고 가정할 수 없을 때(비동기).**

각 알림은 5개 축으로 분해한다: **[누가] × [상태 전이] × [왜] × [급함(→채널)] × [거래성/마케팅성]**.

- **거래성(TRANSACTIONAL)**: 거래 이행에 필수(주문·배송·정산). 수신 동의 없이 발송 가능(정보통신망법 예외).
- **마케팅성(MARKETING)**: 재입고·할인·리마인드. **사전 opt-in 필수**·야간 제한. → 타입별 `category` 플래그 + 사용자 수신설정(preference)이 필요해지는 근거.

## 2. 이벤트 상태 범례

| 태그 | 의미 |
|---|---|
| ✅ 발행중 | outbox 이벤트가 이미 나간다 (현재 `PAYMENT_COMPLETED`뿐) |
| ⚙️ 발행 필요 | 도메인 **상태 전이는 존재**하나 `OutboxService.append`를 안 한다 → 발행 지점 + 핸들러만 추가 |
| 🆕 신규 상태 | 트리거가 될 상태/구독 자체가 없다 → 데이터 모델부터 신설 |

## 3. 매트릭스

### 🛒 Buyer — "내 주문·돈이 어떻게 됐나" (수신자 = `order.memberId`)

| 이벤트 | 트리거(상태 전이) | 왜 | 거래/마케팅 | 상태 |
|---|---|---|---|---|
| 결제 완료 | Payment→PAID / order PENDING→PAID | 확인·안심 | 거래성 | ✅ |
| 배송 시작(송장) | Shipment PAID→SHIPPING (셀러별) → order rollup SHIPPING | **"내 주문 어디?" CS 최대 감소** | 거래성 | ⚙️ |
| 배송 완료 | Shipment→DELIVERED → order DELIVERED | 수령 확인·반품기한 기산 | 거래성 | ⚙️ |
| 취소/환불 완료 | order·item CANCELLED + Payment 환불 | 돈 되돌아옴(신뢰) | 거래성 | ⚙️ |
| 반품 진행(승인/수거/검수/환불) | ReturnStatus REQUESTED→…→REFUNDED | 진행 투명성 | 거래성 | ⚙️ |
| 교환 발송 | Return→COMPLETED(대체품 EXCHANGE 재출고) | 진행 투명성 | 거래성 | ⚙️ |
| **재입고** | product_option 재고 0→N + **구독** | **고관심 전환**(본인 요청) | 마케팅성*(구독=동의) | 🆕 |
| 리뷰 요청 | 배송완료 후 N일(파생·스케줄) | 리텐션·UGC | 마케팅성 | ⚙️ |
| (후속) 장바구니 리마인드·가격인하 | 이탈·가격 변경 | 전환 | 마케팅성 | 🆕 |

### 🏬 Seller — "내가 뭘 해야 하나 + 내 돈" (수신자 = `shipment.sellerId` / 브랜드 소유 셀러)

| 이벤트 | 트리거(상태 전이) | 왜 (비즈니스 임팩트) | 거래/마케팅 | 상태 |
|---|---|---|---|---|
| **새 주문 인입** | Payment→PAID 시 셀러별 Shipment 생성 | **출고 시작 트리거 — 없으면 셀러가 주문을 모른다**(마켓플레이스 필수) | 거래성 | ⚙️ |
| 반품/교환 요청 | ReturnStatus →REQUESTED | 셀러 승인·검수 필요(#3)·방치 시 환불 지연 | 거래성 | ⚙️ |
| 구매자 취소(출고 전) | 셀러 활성 항목 CANCELLED | **출고 중단** 신호(헛배송 방지) | 거래성 | ⚙️ |
| 품절/재고 임박 | product_option 재고 ≤ 임계 | 재입고 결정 → 판매기회 손실 방지 (현재 대시보드 pull만) | 운영 | 🆕 |
| 정산 예정·지급 완료 | SettlementEntry→SCHEDULED / Payout→PAID | 입금 확인·회계 | 거래성 | ⚙️ |
| **정산 net 음수/클로백** | reverseRefunds 음수 엔트리 / payout net<0 | **셀러가 돈을 반환해야 함** → 통지 안 하면 분쟁 | 거래성 | ⚙️ |
| 입점 상태 변경 | Seller ACTIVE↔SUSPENDED | 운영 | 계정 | ⚙️ |

### 🛠️ Platform / Admin — "돈 무결성·운영 이상" (수신자 = ADMIN)

| 이벤트 | 트리거 | 왜 | 거래/마케팅 | 상태 |
|---|---|---|---|---|
| **대사 불일치** 발생 | Mismatch OPEN 생성 | PG↔우리 금액 불일치 = **돈 무결성**, 즉시 조사 (현재 예외 큐 pull) | 운영 | ⚙️ |
| payout/결제 실패·오버셀 시도 | 예외 경로 | 운영 장애 | 운영 | 🆕/⚙️ |
| 선착순 쿠폰 소진 | coupon issuedCount == totalQuantity | 마케팅 성과 신호 | 운영 | ⚙️ |

## 4. 비즈니스 레버 (이 매트릭스가 움직이는 3가지)

1. **운영비 절감** — buyer 배송/취소 알림 → CS 문의↓ · seller 새주문 알림 → 출고 지연↓
2. **매출 회복** — 재입고·리뷰요청·리마인드
3. **돈 무결성 보호** — 대사 불일치·정산 클로백 알림

## 5. 인프라 매핑 & 필요한 모델 변경

**발행(각 상태 전이 지점)**: `OutboxService.append(eventType, aggregateType, aggregateId, payload)`
**소비**: `OutboxEventHandler` 구현체(eventType별) → `NotificationLog` 저장. `EventDispatcher`가 eventType으로 라우팅(한 이벤트 → 여러 핸들러 가능).

**`NotificationLog` 확장 (현재 = id·eventId·type·message):**
- `recipientType`(BUYER/SELLER/ADMIN) + `recipientId`(memberId 또는 sellerId) — 누구 것인지
- `readAt`(nullable) — 읽음 처리·안읽음 뱃지
- `category`(TRANSACTIONAL/MARKETING) — 수신설정·법적 구분
- `link`(예: `/orders/{id}`) — 딥링크(선택)
- ⚠️ **멱등 키 재설계(중요)**: 현재 `event_id` **단독** UNIQUE는 "1 이벤트 → 1 알림"만 맞다. **멀티셀러 주문 1건 = 셀러 N명에게 각각 알림**(1 이벤트 → N 수신자)이므로, 멱등 키를 **`(event_id, recipient_type, recipient_id)` 복합 UNIQUE**로 바꿔야 한다. (기존 PAYMENT_COMPLETED는 1:1이라 안 걸렸던 함정.)

**조회 API(읽는 쪽):**
- Buyer: `GET /api/notifications`(본인·읽음필터·페이지) · `GET /api/notifications/unread-count`(벨 뱃지) · `PATCH /api/notifications/{id}/read` · `PATCH /api/notifications/read-all`
- Seller: `GET /api/seller/me/notifications` (셀러 콘솔 스코프 재사용)
- FE: 헤더 벨 + 안읽음 뱃지 + 드롭다운/목록

**신규 데이터(🆕):**
- 재입고 구독: `stock_subscription`(memberId, optionId) — 품절 옵션 구독 → 재고 증가 시 발행
- (후속) 수신설정 `notification_preference`(memberId, category, enabled) — 마케팅성 opt-in

## 6. 권장 단계 (phased)

| 단계 | 범위 | 검증 포인트 |
|---|---|---|
| **P1 (루프 완성)** | `NotificationLog`에 recipient/readAt/category + 복합 멱등키 · buyer 조회/읽음 API · FE 벨. 기존 `PAYMENT_COMPLETED`를 buyer가 **읽게**. | 이벤트가 인박스까지 도달·읽음 처리 |
| **P2 (buyer 거래알림)** | 배송시작·배송완료·취소/환불·반품 이벤트 발행 + 핸들러 | 상태 전이 → 알림 |
| **P3 (seller 알림)** | 새 주문·반품요청 → seller 스코프. **복합 멱등키(1 이벤트→N 셀러) 실증** | 멀티셀러 fan-out·IDOR(셀러는 자기 것만) |
| **P4 (재입고·전환)** | `stock_subscription` + 재고증가 이벤트 + 마케팅성/구독 | 구독 모델·category 분기 |
| 후속 | admin 대사/운영 알림 · **외부 채널(이메일·카카오 알림톡·푸시)** = 외부 연동이라 오너 학습 트랙(자율 금지) | 채널 어댑터 포트 |

## 7. 열어둔 결정 (정하면 backlog #6 READY로)

1. **1차 수신자 스코프** — buyer만(P1~P2)? seller까지(P3 포함)? → **멀티셀러 차별화를 살리려면 seller 포함 권장**(범위↑)
2. **재입고(P4) 1차 포함 여부** — 마케팅성/구독 모델 도입 시점
3. **수신설정(preference)** — 마케팅 알림이 생기는 시점에 도입(거래성만이면 불필요)
4. **채널** — 1차는 **인앱 인박스 only**(외부 연동 없음·자율 가능). 외부 채널은 후속·오너 트랙
   - ⚠️ 인앱 only는 실도달률 한계(사용자가 사이트를 열어야 봄). 포트폴리오는 **이벤트 루프 완성(아키텍처)** 증명이 목적이라 충분하되, "다음은 알림톡 어댑터"로 확장 스토리를 남긴다.

**권장 1차 MVP**: **P1 + P2 + P3**(buyer 거래알림 + seller 새주문/반품) — 이 조합이 *수신자 스코프 다양성(buyer/seller)* 과 *복합 멱등키 fan-out* 을 함께 증명해, "알림을 비즈니스로 이해했다"는 포트폴리오 근거가 된다. 재입고(P4)는 그 위 확장.
