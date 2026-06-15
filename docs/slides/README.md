# 기술 비교 슬라이드 (Markdown → PPTX)

기술 의사결정/비교 내용을 **마크다운으로 작성**하고, 생성기로 **네이티브 편집 가능한 .pptx**를 찍어낸다.
("도구 자체보다 왜 골랐는지 설명할 수 있는 것"이 포트폴리오 자산 — 비교 과정을 슬라이드로 남기는 용도.)

## 준비물
- Python 3
- `pip install python-pptx`

## 사용법
```
python docs/slides/build_slides.py <input.md> [output.pptx]
```
- 출력 경로 생략 시 입력과 같은 이름의 `.pptx` 생성.
- 예: `python docs/slides/build_slides.py docs/slides/spec-vs-querydsl.md`

## 지원 마크다운 문법 (의도적으로 작은 부분집합)
| 문법 | 결과 |
|---|---|
| `# 제목` (+ 다음 줄들) | 타이틀 슬라이드 (다음 줄 = 부제) |
| `## 제목` | 새 콘텐츠 슬라이드 |
| `### 텍스트` | 슬라이드 안 굵은 리드 줄 |
| `- 불릿` (2칸 들여쓰기) | 불릿(2칸=하위, 4칸=하위하위) |
| `\| a \| b \|` + `\| --- \|` | 표 (GFM) |
| ` ``` ` … ` ``` ` | 코드 블록(고정폭, 연회색 배경) |
| `> 메모` | 작은 회색 노트 |
| `![alt](상대경로.png)` | 이미지 임베드(슬라이드 폭 맞춤). 경로는 .md 기준 상대경로. 파일이 없으면 자리표시자 박스 — mermaid 다이어그램을 PNG로 내보내 넣을 때 유용. |
| `**굵게**`, `` `코드` `` | 인라인 강조/코드 |

> 한 슬라이드(`##`)에 내용이 너무 많으면 아래로 넘칠 수 있다 — 슬라이드당 분량을 적당히 끊고, 미세 조정은 PowerPoint에서.

## 현재 덱
- `spec-vs-querydsl.md` → `spec-vs-querydsl.pptx` — 동적 쿼리: Specification vs QueryDSL (8슬라이드)

## 새 비교 덱 추가
1. `docs/slides/<주제>.md`를 위 문법으로 작성
2. `python docs/slides/build_slides.py docs/slides/<주제>.md` 실행
3. 생성된 `.pptx`를 필요 시 PowerPoint에서 다듬기
