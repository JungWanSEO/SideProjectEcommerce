# 백로그 (자율 진행 큐)

> CLAUDE.md "자율 진행 모드"가 이 파일의 **READY**를 위에서부터 막힐 때까지 처리한다.
> 각 READY = 결정이 끝나 바로 구현 가능한 1 step(외부 프로그램 연동 없음). 완료 시 DONE으로 옮기고 dev-log 기록.
> 외부 프로그램 연동(RabbitMQ·외부 API·새 외부 도구)은 자율 금지 → "함께(학습)"에서 사용자와 직접.

## READY (결정 완료 · 외부 무관 · 자율 진행 가능 · 위에서부터)
- [x] **상품 수정 API (PUT)** (BE) — `PUT /api/products/{id}`(ADMIN): name/price/description/imageUrl/categoryId/brandId 수정. `Product.updateBasics`·카테고리/브랜드 존재 검증·SecurityConfig PUT `/api/products/*` ADMIN. 마이그레이션 0. ✅ dev 병합(332 tests·정적 완결).
- [x] **어드민 상품 이미지·상태 관리 UI** (FE) — `/admin/products`에 상태 드롭다운(상태 API·apiPatch 추가)+이미지 갤러리 추가/삭제(이미지 API)를 옵션 관리와 함께. ✅ dev 병합(FE tsc/lint 0). 브라우저 확인은 사용자.
- [x] **어드민 상품 등록·수정 폼** (FE) — `/admin/products`에 새 상품 등록 폼(POST·옵션 1개·카테고리/브랜드 셀렉트) + 선택 상품 기본정보 수정(PUT 연동·선택 시 자동 채움). ✅ dev 병합(FE tsc/lint 0). 브라우저 확인은 사용자.
- [x] **카테고리 계층화 (2단계)** (BE+FE) — `category.parent_id`(ID 참조·V33)+`CategoryResponse.parentId`+`CategoryService.create` 2단계 검증(부모 없으면400·3단계400)+편의 생성자로 기존 호출부 무변경. FE=`Category.parentId`+어드민 폼 카테고리 셀렉트 부모→자식 들여쓰기. ✅ dev 병합(335 tests·FE 0). ⚠️V33 스키마 → **MySQL 런타임 스모크는 사용자 복귀 후**. (PLP 필터 계층 표시는 후속.)

> **READY 비었음** — 다음 `자율진행`은 멈춰 "백로그 채우기 필요"를 보고함. 다음 후보=「함께(외부)」 RabbitMQ·우편번호·Testcontainers / 카테고리 계층 후속(PLP 필터·어드민 카테고리 관리 화면) / 주문 배송상태 등.

## 함께 (외부 연동 · 학습 — 자율 금지)
- 아웃박스 P2b 실제 RabbitMQ (메시지 브로커)
- 우편번호 검색 API (Daum/Kakao 외부 API)
- ✅ CI (GitHub Actions) — `.github/workflows/ci.yml`(백엔드 gradle test·H2 + FE tsc/lint, push/PR→dev/main). 첫 런은 GitHub Actions 탭. **다음=스케줄 무인 운영(이 위에)**. (후속: Testcontainers 실DB 통합·브랜치 보호 규칙.)

## 결정 필요 (외부 무관이나 결정 미정 — 정하면 READY로)
- 대사 일자별 윈도우 — 설계 결정

## DONE (완료 — 기록)
- [x] **① 옵션 FE 어드민 관리 UI** — `/admin/products`(옵션 추가/수정/삭제). 머지 `6a43281`. ⚠️브라우저 확인 사용자.
- [x] **② 상품 상태 변경 API** — `PATCH /api/products/{id}/status`. 머지 `1f14521`. 정적+런타임 PASS.
- [x] **③ 대표 이미지 갤러리 (ProductImage, V32)** — 엔티티+API+FE ProductGallery. 머지 `7029973`. 정적+MySQL 런타임 스모크 PASS. ⚠️이미지 시드/어드민 이미지 UI(=READY 2번)·브라우저 확인 남음.
