# ADR-0002: 도메인형 패키지 + 애그리거트 간 ID 참조 (DDD-lite)

- **상태**: 채택(Accepted)
- **근거**: [architecture.md §4, §11](../architecture.md) · [도메인 맵](../architecture-diagrams.md#1-도메인-맵--bounded-context와-id-참조-경계)

## 배경 (Context)
기능이 늘어날수록(member·product·order·cart·payment·settlement·seller·coupon 등) 계층(controller/service/...)으로만 가르면 한 기능 변경이 여러 폴더에 흩어진다. 또한 애그리거트(주문·결제·셀러)를 객체 그래프로 직접 연관하면 경계가 흐려지고 불필요한 로딩·결합이 생긴다.

## 결정 (Decision)
패키지를 기술 계층이 아닌 **도메인 단위**(member/product/cart/order/auth/payment/...)로 자르고, 각 도메인 내부에 controller·service·repository·entity·dto를 둔다. 애그리거트 간 참조는 항상 **ID(Long)** 로만 한다 — 예: `Order.memberId`, `OrderItem.productId`, `Payment.orderId`, `Coupon.sellerId` 모두 Long이며 `@ManyToOne` 객체 연관을 쓰지 않는다. 객체 연관(`@OneToMany` cascade/orphanRemoval)은 **애그리거트 내부**(Order↔OrderItem, Cart↔CartItem)에만 허용한다.

## 대안 (Alternatives)
- **(A) 계층형 패키지**(controller/service/... 최상위) — 한 기능 변경이 분산돼 → 탈락.
- **(B) 애그리거트 간 객체 연관(@ManyToOne)** — 결합도↑·경계 모호·불필요 로딩·MSA 분리 어려움 → 탈락.
- 한 사람 1계정 다중 소셜연동 같은 요구가 생기면 별도 테이블로 확장 여지 보존.

## 결과 (Consequences)
- **긍정**: 변경이 한 도메인에 갇힘, 경계 명확, 모듈러 모놀리스라 추후 MSA 분리(객체 그래프가 경계를 안 넘으므로 경계 따라 그대로 쪼갬)·Feign 전환이 쉬움.
- **부정**: 이름 enrich(상품명·셀러명)를 조회 시점에 별도 배치(`findAllById`)로 채워야 하는 수고. `settlement→order` 같은 교차 의존은 서비스 메서드+DTO 경계로만 통과시킴.
