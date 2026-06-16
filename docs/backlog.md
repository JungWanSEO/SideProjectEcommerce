# 백로그 (자율 진행 큐)

> CLAUDE.md "자율 진행 모드"가 이 파일의 **READY**를 위에서부터 막힐 때까지 처리한다.
> 각 READY = 결정이 끝나 바로 구현 가능한 1 step(외부 프로그램 연동 없음). 완료 시 DONE으로 옮기고 dev-log 기록.
> 외부 프로그램 연동(RabbitMQ·외부 API·새 외부 도구)은 자율 금지 → "함께(학습)"에서 사용자와 직접.

## READY (결정 완료 · 외부 무관 · 자율 진행 가능 · 위에서부터)
- (비어 있음) — 다음 `자율진행`은 멈춰 "백로그 채우기 필요" 보고. 후보는 "결정 필요"에서 결정 후 올리거나 "함께(외부)" 학습.

## 함께 (외부 연동 · 학습 — 자율 금지)
- 아웃박스 P2b 실제 RabbitMQ (메시지 브로커)
- 우편번호 검색 API (Daum/Kakao 외부 API)
- ✅ CI (GitHub Actions, `.github/workflows/ci.yml`) — 도입 완료. **다음=스케줄 무인 운영(이 위에)**. 후속: Testcontainers 실DB 통합·브랜치 보호 규칙.

## 결정 필요 (외부 무관이나 결정 미정 — 정하면 READY로)
- 카테고리·브랜드 수정/삭제 API (PUT/DELETE) — 자식 있는 카테고리 삭제·상품 참조 정합 규칙 결정
- 주문 배송 상태 (PAID→SHIPPING→DELIVERED, Flyway enum·어드민 진행) — 전이 가드·어드민 주문화면 결정
- PLP 카테고리 필터 2단계 표시 (FE, 커스텀 Listbox 그룹핑)
- 대사 일자별 윈도우 — 설계 결정

## DONE (완료 — 기록)
- [x] 옵션 API(추가/수정/삭제) + 어드민 옵션 UI — `94b…`/`6a43281` (정적+MySQL 런타임 PASS)
- [x] 상품 상태 변경 API (`PATCH .../status`) — `1f14521` (정적+런타임 PASS)
- [x] 대표 이미지 갤러리 (ProductImage·**V32**) — `7029973` (정적+MySQL 스모크 PASS)
- [x] 상품 수정 API (`PUT /api/products/{id}`) — `94298a5` (332 tests)
- [x] 어드민 상품 상태·이미지 관리 UI + apiPatch — `6bd386d` (FE 0)
- [x] 어드민 상품 등록·수정 폼 — `fa7d30d` (FE 0)
- [x] 카테고리 2단계 계층화 (`category.parent_id`·**V33**) — `3a1bc10` (335 tests). ⚠️V33 MySQL 스모크 복귀 후
- [x] CI (GitHub Actions) — `c55d728` (첫 런 초록불 확인)
- [x] 어드민 카테고리 관리 화면 (`/admin/categories`) — `b84a040` (tsc/lint 0, BE 무변경)
- [x] 어드민 브랜드 관리 화면 (`/admin/brands`) — `b84a040` (tsc/lint 0, BE 무변경)
- ⚠️ 공통 남음: 위 어드민 FE들(상품·카테고리·브랜드) **브라우저 확인** · **V32·V33 MySQL 런타임 스모크**(복귀 후)
