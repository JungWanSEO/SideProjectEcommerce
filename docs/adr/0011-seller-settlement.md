# ADR-0011: 셀러별 정산 분해 (매출 ≠ 셀러 실수령)

- **상태**: 채택(Accepted)
- **근거**: [정산·대사 시퀀스](../architecture-diagrams.md#3-시퀀스--정산--대사-group-by-sum) · [dev-log](../dev-log.md) · Flyway V18~V24

## 배경 (Context)
입점 브랜드 셀렉트샵이 컨셉이라 '매출 = 셀러 실수령'이 아니다. 한 주문이 여러 셀러 상품을 섞을 수 있고, PG 수수료·플랫폼 수수료·쿠폰 할인을 셀러별로 안분해야 셀러가 실제로 얼마를 받는지(net)가 나온다. 운영·정산 깊이가 이 프로젝트의 **차별화 포인트**.

## 결정 (Decision)
결제를 **(결제 × 셀러)** 로 분해한다. `OrderItem`에 `brandId`·`sellerId`를 **주문 시점 스냅샷**(상품→브랜드→셀러 도출)으로 박아 이후 브랜드 재귀속에도 기존 주문 귀속이 불변(이력 안전). `SettlementService.run()`이 주문 항목을 sellerId별 gross로 합산→PG 수수료 안분(잔차는 최대 셀러에)+플랫폼 수수료(`Seller.commissionRate`)→셀러별 `SettlementEntry`(sellerId·platformFee·platformFeeRate·provider·feeRate 스냅샷, UNIQUE(payment_id, seller_id)). net = gross − 수수료 (+PLATFORM 부담 쿠폰이면 할인 환원). 대사는 `pgTransactionId`로 group-by-sum 재작성해 PG 총액과 대조(MATCHED). `Payout`로 기간 단위 묶음 지급(PENDING→PAID).

## 대안 (Alternatives)
- **(A) 주문 단위 단일 정산(셀러 무시)** — 멀티셀러 주문에서 누가 얼마 받는지 불가 → 탈락.
- **(B) 상품의 현재 셀러를 정산 시 조회** — 브랜드 재귀속 시 과거 주문 귀속이 바뀌어 이력 깨짐 → 주문 시점 스냅샷.
- **(C) 수수료를 셀러별 개별 거래로 분리 청구** — PG 거래는 결제 단위라 group-by-sum 대사가 더 자연.

## 결과 (Consequences)
- **긍정**: '매출 ≠ 셀러 실수령'을 1급 회계로 모델링, 멀티셀러 E2E에서 셀러별 net 분해 + 대사 MATCHED(불일치 0)로 증명, 브랜드 재귀속에도 이력 안전.
- **부정**: PG 수수료 안분 잔차 처리(최대 셀러)·UNIQUE 복합키·대사 재작성 등 구현 복잡, 정산이 order 도메인을 서비스+DTO 경계로 읽어야 함.
