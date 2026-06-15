# ADR-0012: 쿠폰 할인 부담 주체 (PLATFORM / SELLER) + gross 보존·payable

- **상태**: 채택(Accepted)
- **근거**: [dev-log](../dev-log.md) · Flyway V25~V27 · [[0011-seller-settlement]]

## 배경 (Context)
쿠폰 할인을 **누가 부담하느냐**가 셀러 실수령과 플랫폼 손익을 가른다(셀러 부담이면 셀러 net↓, 플랫폼 부담이면 플랫폼 마케팅비). 또 할인을 적용해도 정산이 항목 원가를 안분할 수 있게 **매출(gross)을 보존**해야 하고, 부분환불 시 과다환불이 없어야 한다.

## 결정 (Decision)
`Coupon`을 4축으로 둔다: **fundedBy**(PLATFORM/SELLER) · **적용범위 sellerId**(null=플랫폼 와이드 / 값=셀러 한정) · **issueType**(PUBLIC 공개코드·무제한 / ISSUED 발급형·지갑·단일사용) · **할인종류**(정액/정률+상한).
- 체크아웃은 **gross(총액)를 보존**하고 `Order`에 discountAmount·couponCode·couponFundedBy·couponSellerId를 스냅샷, `payableAmount = 총액 − 할인`으로 결제는 payable을 청구.
- 정산 Step2: 할인 안분(플랫폼와이드 = 매출비례 proRate, 셀러한정 = 전액) + net = gross − 수수료 + (PLATFORM 부담이면 할인 환원). `grossAmount`=할인 후 셀러 몫이라 대사 group-by-sum 그대로 MATCHED.
- 정산 Step2b: **항목별 할인 안분**(`Order.discountShares`, 매출비례·잔차 최대항목)으로 **항목 실효가 = 소계 − share**가 환불·정산의 단일 출처(과다환불 해결, Σ실효가 = 결제액).
- 취소 시 ISSUED 쿠폰은 복원(release).

## 대안 (Alternatives)
- **(A) 할인을 payable에만 반영하고 gross 미보존** — 정산이 항목 원가 안분 불가, 부담주체 회계 불가 → 탈락.
- **(B) 환불을 항목 gross로** — 할인 주문에서 과다환불·정산 상계 skip → 항목 실효가 단일출처로 재설계.
- **(C) PUBLIC 코드만(발급 없음)** — 무제한 재사용·단일사용 불가라 하이브리드(PUBLIC+ISSUED) 추가.

## 결과 (Consequences)
- **긍정**: 부담주체별로 셀러 실수령·플랫폼 손익이 정확히 갈림, gross 보존으로 정산 안분 가능, 어떤 취소 순서에도 Σ실효가 = 결제액, 발급형 단일사용·취소 복원. 멀티셀러+할인 E2E에서 net 환원·대사 MATCHED 검증.
- **부정**: 할인 안분·역분개 상계·issueType 분기 등 회계 로직 복잡, 마이그레이션 3개.
