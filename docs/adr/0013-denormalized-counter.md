# ADR-0013: 위시리스트/리뷰 비정규화 카운터 (원자 UPDATE)

- **상태**: 채택(Accepted)
- **근거**: [dev-log](../dev-log.md) · `ProductRepository` · [오답노트](../../.claude/skills/mistake-log/오답노트.md)

## 배경 (Context)
상품 목록·상세에서 **찜 수(인기순 정렬 신호)** 와 **평점(개수·평균)** 을 보여줘야 한다. 매 조회마다 wishlist/review 테이블을 COUNT/AVG 집계하면 비싸고 N+1을 유발한다. 동시에 여러 사용자가 찜/리뷰를 추가·삭제하면 카운터 갱신에 경합이 생긴다.

## 결정 (Decision)
`Product`에 **비정규화 카운터**(ratingCount·ratingSum·wishlistCount)를 두고, 변경 도메인(review·wishlist)이 **원자 UPDATE**로 갱신한다. `ProductRepository`의 `@Modifying(flushAutomatically=true, clearAutomatically=true)` `@Query`로 `set wishlistCount = wishlistCount + 1`, `ratingCount = ratingCount + 1, ratingSum = ratingSum + :rating`, 감소는 `where ... and count > 0` 가드. 평균 = ratingSum/ratingCount(반올림, count 0이면 0.0). 정렬은 인기순 = wishlistCount 컬럼, 평점평균순은 컬럼 없이 CASE 식(리뷰 0은 -1로 맨 뒤). 1인 1상품 1리뷰·(member, product) UNIQUE 제약.

## 대안 (Alternatives)
- **(A) 조회 시마다 COUNT/AVG 집계** — 비싸고 N+1, 인기순/평점순 정렬에 부적합 → 탈락.
- **(B) 애플리케이션에서 read-modify-write로 카운터 갱신** — 동시성 경합·lost update 위험 → 탈락(원자 UPDATE로 해소).
- **(C) 캐시/별도 집계 테이블** — 이 규모엔 과함.

## 결과 (Consequences)
- **긍정**: 조회 시 추가 집계 없이 카운터·평균 노출, 원자 UPDATE로 동시 증감 안전, wishlistCount로 인기순 정렬(다음 개인화 추천의 행동 신호).
- **부정**: 카운터와 실제 행 수가 어긋날 수 있는 비정규화 일관성 부담(가드·테스트로 완화).
- 🐞 **함정 기록**: `@Modifying`의 `clearAutomatically`가 flush 전 컨텍스트를 비워 보류 삭제가 유실된 버그를 런타임에서 발견 → `flushAutomatically=true`로 수정([오답노트](../../.claude/skills/mistake-log/오답노트.md)).
