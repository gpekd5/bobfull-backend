# BobFull AI 작업 진입점

이 파일은 모든 BobFull AI 작업에서 먼저 적용하는 공통 진입점이다. 세부 절차를 중복하지 않고, 안전 경계·상태·Human 책임과 다음에 읽을 문서만 결정한다.

## 1. Context Loading

기본 Context는 `AGENTS.md`와 실제 요청이다. Issue 작업이면 실제 Issue를 함께 읽는다.

| 현재 단계 | 추가로 읽는 문서 |
|---|---|
| 새 Issue 최초 처리 | `skills/bobfull-onboarding/SKILL.md` |
| Issue 흐름 또는 Refactor Learning Mode 판단 | `docs/060-ai/ai-workflow.md` |
| `status:in-progress` 이후 구현 | `docs/060-ai/ai-implementation-guide.md` |
| PR 생성·본문 갱신 | `skills/bobfull-pr-explain/SKILL.md`, `.github/pull_request_template.md` |
| Draft PR 생성 또는 새 Push 뒤 AI Review | `skills/bobfull-pr-review/SKILL.md` |
| Human 이해 질문 작성·검토 | `docs/060-ai/ai-review-guide.md` |
| 개선 효과를 주장하는 작업 | `docs/110-records/evidence/v3/README.md` |

- 같은 Issue를 재처리할 때 onboarding Skill을 형식적으로 다시 읽지 않는다. 계약이 크게 바뀌어 필요한 기준 문서를 다시 선택해야 할 때만 재확인한다.
- 구현 전에는 PR Explain·AI Review 문서를 선행 로딩하지 않는다.
- 비고도화 작업에서는 Evidence 표준과 과거 Evidence를 읽지 않는다.
- PR 단계에서는 Issue 계약, 최신 Diff, 검증 결과와 PR 전용 문서를 중심으로 읽고 구현 문서를 처음부터 다시 읽지 않는다.
- 링크되어 있다는 이유만으로 문서 전체를 읽지 않는다. 아래 문서 라우팅에서 현재 작업과 직접 관련된 원본만 선택한다.

최상위 `README.md`는 프로젝트 소개 문서다. 정책, API, DB, 상태, 인증·권한, 트랜잭션, Issue 범위, AI 상태 흐름, Merge 조건의 판단 근거로 사용하지 않는다.

## 2. 명령과 단계

```text
Issue #번호 구현하라
PR #번호 검토하라
새 Issue 초안 작성하라
이 초안으로 Issue 생성하라
```

- `새 Issue 초안 작성하라`: GitHub를 변경하지 않고 초안만 제시한다.
- `이 초안으로 Issue 생성하라`: Human이 확인한 초안으로 실제 Issue를 생성한다.
- `Issue #번호 구현하라`: Issue·상태·연결 PR을 확인하고 현재 Issue 단계부터 진행한다.
- `PR #번호 검토하라`: 최신 Head와 Diff를 기준으로 PR을 재검토하거나 범위 안 수정 사항을 처리한다.
- Issue 명령에서 연결 PR이 이미 있으면 PR Diff·댓글을 수정하지 않고 `PR #번호 검토하라`가 필요하다고 보고한다.

새 Issue 최초 처리 순서는 다음과 같다.

```text
AGENTS.md + 실제 Issue
→ onboarding Skill로 필요한 문서 선택
→ 관련 코드·테스트·브랜치 확인
→ Issue·문서·코드 직접 충돌 확인
→ 현재 상태에 맞는 단계 수행
```

## 3. 상태 관리

실제 실행 상태의 유일한 기준은 GitHub `status:*` Label이다. Issue 본문의 상태 문자열은 현재 상태로 사용하지 않는다. 상태 변경 전 기존 `status:*` Label을 모두 제거하고 다음 중 하나만 적용한다.

| Label | 의미 |
|---|---|
| `status:human-answer-required` | Human 답변·정책 판단·범위 결정이 필요하거나 충돌로 중단 |
| `status:in-progress` | 계약 확인 후 구현·검증·PR 갱신 진행 |
| `status:final-human-review` | 최신 Head 구현·검증·AI Review 완료 후 Human 최종 검토 대기 |

Issue 본문에는 목적·범위·완료 조건과 Human 질문·답변을 보존한다. AI 검토, 보완 설명, 최종 계약, 착수·완료 기록은 Issue 댓글에 남긴다.

- 필요한 Human 답변이 없거나 불명확하면 `status:human-answer-required`로 중단한다.
- 답변을 실제 코드·문서와 대조해 충돌이 없으면 같은 실행에서 `status:in-progress`로 전환하고 진행한다.
- PR을 읽고 보고만 하면 기존 Label을 유지한다. 파일 수정이 시작된 PR 작업은 연결된 모든 Issue를 `status:in-progress`, 최신 Head 검증·Review 완료 뒤에는 `status:final-human-review`로 전환한다.

## 4. 브랜치 안전

- `main`, `master`, `develop`에서는 코드·테스트·설정·문서 파일을 수정하지 않는다.
- 파일 수정 전에 `git branch --show-current`와 작업 트리를 확인한다.
- 대상 Issue의 기존 브랜치가 있으면 사용하고, 없으면 최신 `develop` 기준으로 `<type>/<issue-number>-<summary>` 형식의 Issue 전용 브랜치를 만든다.
- 다른 Issue 브랜치에서 작업 트리가 깨끗하면 최신 `develop`으로 전환·갱신한 뒤 새 Issue 브랜치를 만든다.
- 다른 Issue 브랜치의 미커밋 변경은 새 Issue 브랜치로 임의 이동하지 않는다. 발견하면 수정을 중단하고 Human 판단을 요청한다.
- 보호 브랜치에 대상 Issue 관련 미커밋 변경이 있을 때만 변경을 유지한 채 Issue 브랜치로 이동한다.
- 브랜치 생성·전환 뒤 현재 브랜치를 다시 확인한다. 실패하거나 대상 Issue 브랜치가 아니면 구현하지 않는다.
- 브랜치 생성·전환에 실패하면 `status:human-answer-required`로 중단하고 원인을 보고한다.

Git·Issue·Commit·PR 세부 형식은 `docs/050-engineering/github-rules.md`를 따른다.

## 5. Human 책임과 금지 경계

다음 판단은 Human 책임이다.

- 정책 변경
- API 계약 변경
- DB·상태 모델 재결정
- 권한·보안 정책 결정
- 트랜잭션 경계 재결정
- Human 답변이 필요한 Issue 결정
- 최종 Merge

담당자 AI는 Human 답변이나 Human Review를 대신 작성하지 않는다. AI 보완은 `AI 보완 설명`으로 구분하며 Approve와 Merge를 수행하지 않는다. 별도 GitHub Human Approve는 현재 필수 Merge Gate가 아니지만 Human의 판단과 최종 Merge 책임은 유지된다.

다른 팀원 AI Review의 존재 여부를 작업 시작·수정·Merge 조건으로 사용하지 않는다.

PR 댓글은 작성 주체와 관계없이 실제 코드 근거로 처리한다.

- 범위 안 결함: 수정·재검증
- 설명 요청: 확인 결과 답변
- 정책·API·DB·권한·트랜잭션 재결정: Human 판단 요청
- 근거 없는 범위 밖 제안: 반영하지 않고 이유 기록

## 6. 작업 유형 라우팅

### Refactor Learning Mode

적용 대상, 제외 기준, Human 질문 3축과 재개 조건의 단일 기준은 `docs/060-ai/ai-workflow.md`다. 여기서는 새 Label·approval 단계·Reviewer 역할을 만들지 않고, Human 답변 전 파일을 수정하지 않는다는 경계만 유지한다.

### 구현과 테스트

실제 구현 절차는 `docs/060-ai/ai-implementation-guide.md`, 코드 규칙은 `docs/050-engineering/code-convention.md`, 테스트 규칙은 `docs/050-engineering/test-convention.md`에서 현재 변경에 필요한 부분만 읽는다.

### Enhancement와 Evidence

성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI 등 개선 효과를 주장할 때만 `docs/110-records/evidence/v3/README.md`와 해당 Issue Evidence를 읽는다. 실행하지 않은 결과를 만들거나 과거 Evidence 전체를 선행 로딩하지 않는다.

### PR Explain과 AI Review

PR 본문은 `skills/bobfull-pr-explain/SKILL.md`와 최신 `.github/pull_request_template.md`를 따른다. Draft PR 생성 또는 새 Push 직후 `skills/bobfull-pr-review/SKILL.md`로 최신 Head를 독립 검토한다. 현재 Merge Gate는 `.github/pull_request_template.md`를 기준으로 하며 최종 Merge는 Human이 수행한다.

## 7. 기준 문서 선택

| 변경 대상 | 필요한 원본 |
|---|---|
| 프로젝트 정책·버전·역할 | `docs/010-product/project-context.md` |
| HTTP·WebSocket 계약 | `docs/020-api/bobfull-api-spec-complete.md`와 필요한 상세 API 문서 |
| 데이터 모델·정합성 | `docs/030-data/erd.md` |
| 책임 경계 | `docs/040-architecture/architecture.md`, 필요 시 `domain-dependencies.md` |
| 중요한 기술 결정 | `docs/040-architecture/adr/README.md`와 관련 ADR만 |
| Issue 제목 | `docs/050-engineering/issue-title-rules.md` |

API 변경은 API 명세와 관련 정책·ERD를, 도메인 정책 변경은 프로젝트 컨텍스트·API·ERD를, 데이터 모델 변경은 ERD와 관련 API를 함께 확인한다. 기준 문서가 충돌하면 임의로 선택하지 않는다.

## 8. 공통 안전 규칙

- 한 번에 하나의 Issue만 처리하고 범위 밖 기능·리팩터링을 추가하지 않는다.
- 실행하지 않은 테스트·build·직접 검증·Evidence를 `PASS`로 기록하지 않는다.
- 전체 build가 실패하면 완료로 표현하지 않는다.
- 비밀정보와 실제 개인정보를 Commit·PR·Evidence에 포함하지 않는다.
- 기존 문서는 별도 `SUMMARY`, `UPDATED`, `FINAL` 사본을 만들지 않고 직접 수정한다.
- 승인 없이 임시 폴더, 압축·Base64 파일, trigger 파일, 일회성 GitHub Actions Workflow를 저장소에 추가하지 않는다.
- 완료 전 변경 파일과 Diff를 다시 확인하고 요청하지 않은 파일을 포함하지 않는다.
- API Response 계산값을 근거 없이 DB 컬럼으로 중복 저장하지 않는다.
- `READY` Payment 좌석 선점·만료 정책 변경이나 `Settlement`, `SeatHold`, `WebhookEvent` 엔티티 추가는 Human 판단과 기준 문서 반영 없이 수행하지 않는다.

## 9. 즉시 중단 조건

- 확정 문서·Issue·코드 충돌
- Human 답변이 모호하거나 핵심 결정 누락
- 최종 계약 기록 뒤 계약 변경
- 다른 담당자 계약 변경 필요
- 새로운 정책·API·DB·인프라 결정 필요
- 데이터 정합성·권한·보안·손실 위험 발견
- 핵심 검증 환경·권한 부재
- Issue 범위 밖 변경 필요

이미 존재하는 PR은 확인 가능한 최신 Diff까지 검토하고 위험과 필요한 Human 결정을 보고한다.
