# 아키텍처 결정 기록 (ADR)

> **ADR(Architecture Decision Record)** = "왜 이렇게 만들었나"를 결정 1개 = 1파일로 남긴 기록.
> dev-log가 *시간순 changelog*라면, ADR은 *결정의 근거*다 — 면접/발표에서 "이건 왜 이렇게 했어요?"에 바로 펼칠 한 장.
>
> 각 문서 형식: **배경(Context) → 결정(Decision) → 대안(Alternatives) → 결과(Consequences)**.
> 시각 자료는 [아키텍처 다이어그램](../architecture-diagrams.md), 시간순 기록은 [dev-log](../dev-log.md), 상세 설계는 [architecture.md](../architecture.md).

| # | 결정 | 한 줄 요약 |
|---|---|---|
| [0001](0001-spring-boot-3.5.md) | Spring Boot 3.5 고정 | 안정·풍부한 자료 위해 3.5.14 pin (4.0 의도적 회피) |
| [0002](0002-domain-package-id-reference.md) | 도메인형 패키지 + ID 참조 | 패키지=도메인 단위, 애그리거트 간 참조는 ID(Long)만 |
| [0003](0003-stock-optimistic-lock.md) | 재고 동시성 = @Version + 재시도 | 낙관적 락 + 새 트랜잭션 재시도로 초과판매 0 증명 |
| [0004](0004-product-option-sku.md) | 상품 옵션 = SKU | 사이즈 단축, 색상은 별도 상품 / 재고를 옵션 단위로 |
| [0005](0005-jwt-httponly-cookie.md) | 인증 = JWT httpOnly 쿠키 | access/refresh 회전 + jti, XSS·CSRF 동시 방어 |
| [0006](0006-secrets-env-flyway.md) | 운영 하드닝 = 시크릿 env + Flyway | 12-factor 시크릿 + ddl validate, 스키마=마이그레이션 |
| [0007](0007-dynamic-search-querydsl.md) | 동적 검색 = QueryDSL | 타입 안전 동적 where/정렬, .NET LINQ 전이 |
| [0008](0008-payment-port-adapter.md) | 결제 = 포트-어댑터 | PaymentGateway 포트 + 모의 PG, 실 PG 무중단 교체 |
| [0009](0009-transactional-outbox.md) | 트랜잭셔널 아웃박스 | dual-write 해소, 상태↔이벤트 한 커밋 + 멱등 소비 |
| [0010](0010-multi-pg-routing.md) | 다중 PG 라우팅 3전략 | 클라이언트 선택 / 페일오버 / 비용기반 + 요율 단일 출처 |
| [0011](0011-seller-settlement.md) | 셀러별 정산 분해 | 매출 ≠ 셀러 실수령, (결제 × 셀러) 분해 + 대사 |
| [0012](0012-coupon-funded-by.md) | 쿠폰 할인 부담 주체 | PLATFORM/SELLER 부담 + gross 보존·payable |
| [0013](0013-denormalized-counter.md) | 비정규화 카운터 | 평점·찜 수를 원자 UPDATE로, 조회 시 집계 회피 |

상태 표기: 모두 **채택(Accepted)**. 바뀌면 새 ADR로 대체하고 옛 ADR은 *대체됨(Superseded)* 으로 표시한다(ADR 관례).
