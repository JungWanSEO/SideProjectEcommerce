# 백로그 (자율 진행 큐)

> CLAUDE.md "자율 진행 모드"가 이 파일의 **READY**를 위에서부터 막힐 때까지 처리한다.
> 각 READY = 결정이 끝나 바로 구현 가능한 1 step(외부 프로그램 연동 없음). 완료 시 DONE으로 옮기고 dev-log 기록.
> 외부 프로그램 연동(RabbitMQ·외부 API·새 외부 도구)은 자율 금지 → "함께(학습)"에서 사용자와 직접.

## READY (결정 완료 · 외부 무관 · 자율 진행 가능 · 위에서부터)
- [ ] **상품 수정 API (PUT)** (BE) — `PUT /api/products/{id}`(ADMIN): name/price/description/imageUrl/categoryId/brandId 수정(옵션·이미지·상태는 각자 API). `Product.updateBasics`. 카테고리/브랜드 존재 검증(create와 동일). 마이그레이션 0. → 상품 CRUD 완성(현재 등록만 가능·수정 없음).
- [ ] **어드민 상품 이미지·상태 관리 UI** (FE) — `/admin/products`에 선택 상품 이미지 추가/삭제(이미지 API)+상태 드롭다운(상태 API)을 옵션 관리 옆에. → 갤러리·상태를 화면에서 데모 가능(현재 이미지 0). 브라우저 확인은 사용자.
- [ ] **어드민 상품 등록·수정 폼** (FE · 위 "상품 수정 API" 뒤) — `/admin/products`에 새 상품 등록 폼 + 기본정보 수정(PUT 연동). 옵션은 등록 후 옵션 관리로. 카테고리/브랜드는 평면 선택(계층화는 별도). 브라우저 확인은 사용자.
- [ ] **카테고리 계층화 (2단계)** (BE+FE) — `category.parent_id`(self-ref ID, nullable)+Flyway V33, `CategoryResponse.parentId`, 카테고리 목록 부모→자식 구조 노출, FE 2단계 표시. 상품 필터는 exact(부모 선택 시 자식 포함 expansion은 후속). ⚠️V33 스키마 → MySQL 런타임 스모크는 사용자 복귀 후.

## 함께 (외부 연동 · 학습 — 자율 금지)
- 아웃박스 P2b 실제 RabbitMQ (메시지 브로커)
- 우편번호 검색 API (Daum/Kakao 외부 API)
- CI (GitHub Actions — 새 외부 도구, 학습 가치)

## 결정 필요 (외부 무관이나 결정 미정 — 정하면 READY로)
- 대사 일자별 윈도우 — 설계 결정

## DONE (완료 — 기록)
- [x] **① 옵션 FE 어드민 관리 UI** — `/admin/products`(옵션 추가/수정/삭제). 머지 `6a43281`. ⚠️브라우저 확인 사용자.
- [x] **② 상품 상태 변경 API** — `PATCH /api/products/{id}/status`. 머지 `1f14521`. 정적+런타임 PASS.
- [x] **③ 대표 이미지 갤러리 (ProductImage, V32)** — 엔티티+API+FE ProductGallery. 머지 `7029973`. 정적+MySQL 런타임 스모크 PASS. ⚠️이미지 시드/어드민 이미지 UI(=READY 2번)·브라우저 확인 남음.
