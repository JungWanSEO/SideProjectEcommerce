# 백로그 (자율 진행 큐)

> CLAUDE.md "자율 진행 모드"가 이 파일의 **READY**를 위에서부터 막힐 때까지 처리한다.
> 각 READY = 결정이 끝나 바로 구현 가능한 1 step(외부 프로그램 연동 없음). 완료 시 `[x]` + dev-log 기록.
> 외부 프로그램 연동(RabbitMQ·외부 API·새 외부 도구)은 자율 금지 → "함께(학습)"에서 사용자와 직접.

## READY (결정 완료 · 외부 무관 · 자율 진행 가능)
- [x] **① 옵션 FE 어드민 관리 UI** (FE) — `/admin/products` 신설: 상품 목록(GET /api/products) + 선택 상품의 옵션 추가/수정/삭제(옵션 API 연동·인라인·기존 /admin 톤). 브라우저 확인은 사용자. tsc/lint까지 자율. ✅ dev 병합(브라우저 확인 사용자 몫).
- [ ] **② 상품 상태 변경 API** (BE) — `PATCH /api/products/{id}/status`(ADMIN)·`Product.changeStatus`. 등록 후 ON_SALE↔SOLD_OUT↔DISCONTINUED 전환. 마이그레이션 0.
- [ ] **③ 대표 이미지 갤러리 (ProductImage)** (BE+FE) — 새 `product_image`(product_id·url·sort_order)+Flyway V32, `ProductResponse.imageUrls`, 관리자 추가/삭제, FE 상세 썸네일 갤러리. 기존 imageUrl=대표 유지(갤러리는 추가분). ⚠️스키마 변경 → MySQL 런타임 스모크는 사용자 복귀 후.

## 함께 (외부 연동 · 학습 — 자율 금지)
- 아웃박스 P2b 실제 RabbitMQ (메시지 브로커)
- 우편번호 검색 API (Daum/Kakao 외부 API)
- CI (GitHub Actions — 새 외부 도구, 학습 가치)

## 결정 필요 (외부 무관이나 결정 미정 — 정하면 READY로)
- 카테고리 계층화 — 깊이(2단계?)·FE 노출 결정
- 대사 일자별 윈도우 — 설계 결정
