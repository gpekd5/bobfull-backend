---
name: bobfull-onboarding
description: BobFull 저장소의 새 Issue를 처음 처리할 때 현재 단계와 작업 유형에 필요한 기준 문서만 선택한다.
---

# BobFull 최초 온보딩

## 사용 시점

- BobFull 저장소에서 처음 작업할 때
- 새로운 Issue를 처음 인계받았을 때
- Issue 계약 변경으로 기준 문서를 다시 선택해야 할 때

같은 Issue 재처리, PR 생성·본문 갱신, `PR #번호 검토하라`, Ready 전 확인에는 사용하지 않는다.

## 필수 입력

- 대상 Issue 번호와 실제 본문·댓글·`status:*` Label
- 요청받은 현재 단계
- 사용자가 명시한 작업 범위
- 현재 저장소·브랜치·작업 트리

정보가 없으면 추측하지 않고 누락을 보고한다.

## 실행 순서

1. 이미 읽은 `AGENTS.md`의 브랜치·상태 안전 규칙으로 현재 저장소와 작업 트리를 확인한다.
2. 대상 Issue와 연결 PR 유무를 확인한다.
3. 아래 표에서 현재 단계와 변경 대상에 필요한 문서만 선택한다.
4. 선택한 문서, 실제 코드·테스트와 Issue 사이의 직접 충돌만 확인한다.
5. 현재 수행 가능한 단계와 다음 Human 게이트를 보고한다.

## 문서 선택

| 조건 | 읽는 문서 |
|---|---|
| 일반 Issue의 구현 착수 | `docs/060-ai/ai-implementation-guide.md` |
| 의미 있는 Refactor Issue 최초 처리·재개 | `docs/060-ai/ai-workflow.md` |
| Human 이해 질문 작성·검토 | `docs/060-ai/ai-review-guide.md` |
| Branch·Issue·Commit·PR 규칙 필요 | `docs/050-engineering/github-rules.md` |
| 코드 작성 | `docs/050-engineering/code-convention.md`, 필요 시 `common-skeleton-guide.md` |
| 테스트 작성·결과 기록 | `docs/050-engineering/test-convention.md` |
| 개선 효과 주장 | `docs/110-records/evidence/v3/README.md`와 해당 Issue Evidence |
| 정책·버전·역할 변경 | `docs/010-product/project-context.md` |
| HTTP·WebSocket 계약 변경 | `docs/020-api/bobfull-api-spec-complete.md`와 관련 상세 문서 |
| DB·데이터 정합성 변경 | `docs/030-data/erd.md` |
| 책임·도메인 경계 변경 | `docs/040-architecture/architecture.md`, 필요 시 `domain-dependencies.md` |
| 중요한 기술 선택 | `docs/040-architecture/adr/README.md`와 관련 ADR만 |
| Issue 제목 작성 | `docs/050-engineering/issue-title-rules.md` |

PR 단계는 이 Skill의 범위가 아니다.

- PR 생성·본문 갱신: `skills/bobfull-pr-explain/SKILL.md`와 최신 PR template
- Draft PR 생성 또는 새 Push 뒤 Review: `skills/bobfull-pr-review/SKILL.md`

## 선택 원칙

- 모든 문서를 무조건 읽지 않는다.
- 링크가 아니라 실제 변경 영향으로 문서를 선택한다.
- 과거 Evidence, 전체 ADR, API 상세 문서 전체를 선행 로딩하지 않는다.
- 구현·PR·Review 문서를 한 번에 묶어 읽지 않는다.
- 원본 규칙을 이 Skill에 복제하지 않는다.
- Issue 범위 밖 문서 충돌을 찾기 위해 탐색을 무한히 확장하지 않는다.

## 최초 보고

- 대상 Issue와 현재 상태
- 현재 브랜치와 작업 트리
- 선택한 기준 문서와 선택 이유
- 직접 충돌
- 현재 수행 가능한 단계
- 다음 Human 게이트 또는 HOLD 사유
