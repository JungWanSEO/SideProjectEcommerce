# ADR-0007: 동적 검색 = QueryDSL

- **상태**: 채택(Accepted)
- **근거**: [architecture.md](../architecture.md) · `ProductRepositoryImpl` · `SettlementRepositoryImpl` · [기술비교 슬라이드](../slides/spec-vs-querydsl.md)

## 배경 (Context)
상품 목록에 키워드·가격대·카테고리·브랜드·사이즈·정렬 등 **선택적 필터**가 누적됐다. 조건 유무에 따라 WHERE가 동적으로 바뀌어야 해 메서드명 쿼리나 문자열 JPQL로는 조합 폭발·타입 안전성 문제가 생긴다.

## 결정 (Decision)
동적 조건 검색은 **QueryDSL 5.1.0(jakarta)** 로 구현한다. 커스텀 리포 프래그먼트 `ProductRepositoryImpl`에서 `BooleanBuilder`/동적 where(키워드·가격대·옵션 EXISTS 사이즈 필터)와 정렬(평점평균은 컬럼 없이 CASE 식, 리뷰 0은 -1로 맨 뒤)을 처리한다. 정산 셀러·상태·기간 search/summary에도 재사용한다.

## 대안 (Alternatives)
- **(A) Spring Data 메서드명 쿼리** — 조건 조합마다 메서드 폭발 → 탈락.
- **(B) 문자열 JPQL/네이티브** — 타입 안전성·리팩터 내성 부족 → 탈락.
- **(C) JPA Criteria/Specification** — 장황하고 가독성 낮아 → 탈락.

## 결과 (Consequences)
- **긍정**: 타입 안전·컴파일 검증·가독성, .NET LINQ 경험 전이가 쉬움, 정산 집계 쿼리에도 재사용.
- **부정**: QueryDSL 커스텀 프래그먼트는 Spring Data 규약상 구현체 이름이 반드시 `<리포명>Impl`이어야 함(프레임워크 강제). Q타입 생성 빌드 설정 필요.
