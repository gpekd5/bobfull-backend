# BobFull AI 작업 진입점

이 파일은 BobFull 저장소에서 사용하는 담당자 AI 에이전트의 공통 실행 규칙이다.

담당자 AI는 Issue 정리·구현·검증·PR 자체 검토·리뷰 반영을 수행한다. Human은 필요한 이해도 답변과 정책 판단, 최종 Merge를 책임진다. V3 Sprint Mode에서는 별도 GitHub Human Approve를 필수 Merge Gate로 두지 않지만, 이 원칙은 Human의 판단 책임을 없애지 않는다.

## 1. 기본 Skill 적용 방식

저장소에 접근할 수 있는 담당자 AI의 기본 경로는
[`skills/bobfull-onboarding/SKILL.md`](skills/bobfull-onboarding/SKILL.md)를 직접 읽고 적용하는 방식이다.

- Skill 등록·자동 매칭 기능을 지원하는 AI 환경에서는 선택적으로 등록할 수 있다.
- 그러나 등록 여부는 BobFull 작업의 시작 조건이 아니며, 팀원이 별도 온보딩 명령을 먼저 실행할 필요도 없다.
- 새 Issue를 처음 처리할 때 담당자 AI가 이 파일의 지시에 따라 Skill을 직접 읽고 적용한다.

```text
Issue #번호 구현하라
새 Issue 초안 작성하라
이 초안으로 Issue 생성하라
```

`새 Issue 초안 작성하라`는 GitHub를 변경하지 않고 대화창에 초안을 제시하는 명령이며, Human이 `이 초안으로 Issue 생성하라`를 명시한 경우에만 실제 Issue를 생성한다. 상세 절차는 `docs/ai/ai-workflow.md`, 담당자 AI 실행 규칙은 `docs/ai/ai-implementation-guide.md`를 따른다.

## 2. Issue 단계 실행 명령과 새 Issue 최초 처리

팀원은 새 Issue와 기존 Issue 모두 다음 명령만 사용한다.

```text
Issue #번호 구현하라
```

담당자 AI가 새로운 Issue를 처음 처리하면 다음 순서로 동작한다.

```text
AGENTS.md 확인
→ 대상 Issue·연결 PR과 현재 상태 확인
→ skills/bobfull-onboarding/SKILL.md 직접 읽기·적용
→ 대상 Issue에 직접 필요한 기준 문서만 선택
→ 관련 코드·테스트·브랜치 상태 확인
→ 문서·Issue·코드 충돌 확인
→ 현재 Issue 상태에 맞는 단계 수행
```

### 브랜치 안전 규칙

- `main`, `master`, `develop`에서는 코드·테스트·설정·문서 파일을 수정하지 않는다.
- 파일 수정 전에 반드시 `git branch --show-current`와 작업 트리 상태를 확인하고, 현재 브랜치가 대상 Issue 전용 브랜치가 아니면(보호 브랜치이거나 다른 Issue의 작업 브랜치여도) 어떤 파일도 수정하지 않는다.
- 대상 Issue에 연결된 기존 작업 브랜치가 있으면 해당 브랜치로 전환한다.
- 대상 Issue 브랜치가 없고 Issue가 구현 가능 상태이며 작업 트리가 깨끗하면, 현재 브랜치가 다른 Issue의 작업 브랜치여도 먼저 원격 기준 최신 `develop`으로 전환·갱신한 뒤 그 위에서 별도 Human 승인 없이 Issue 전용 브랜치를 생성한다. Issue 전용 브랜치는 항상 최신 `develop`을 기준으로 생성한다.
- 브랜치명은 저장소의 기존 규칙을 따르며 기본 형식은 `<type>/<issue-number>-<summary>`로 한다.
- 다른 Issue의 작업 브랜치에서 미커밋 변경이 발견되면 새 Issue 브랜치로 임의 이동하지 않고 추가 수정을 중단한 뒤 `status:human-answer-required`로 전환해 Human 판단을 요청한다.
- 보호 브랜치에서 대상 Issue와 관련된 미커밋 변경이 발견된 경우에만 변경을 유지한 채 Issue 브랜치로 이동한다.
- 브랜치 생성·전환 후 현재 브랜치를 다시 확인하고, 대상 Issue 브랜치가 아니면 구현을 시작하지 않는다.
- 브랜치 생성·전환에 실패하면 `status:human-answer-required`로 중단하고 원인을 보고한다.

온보딩 Skill은 대상 Issue·현재 상태 확인, 필요한 기준 문서 선택, 불필요 문서 제외,
직접 충돌 확인, 현재 수행 가능한 단계와 다음 Human 게이트 판단만 담당한다.
구현·코드 리뷰·Merge를 직접 수행하는 별도 워크플로우가 아니다.

최상위 `README.md`는 면접관·채용 담당자·외부 방문자·팀원 Human을 위한 프로젝트 소개 문서다.
담당자 AI는 README를 정책, API, DB, 상태, 인증·권한, 트랜잭션, Issue 범위,
AI 상태 흐름, 승인·Merge 조건의 판단 근거로 사용하지 않는다.
실제 판단 근거는 `AGENTS.md`, 대상 Issue, onboarding Skill이 선택한 기준 문서, 실제 코드와 테스트다.

## 3. 같은 Issue 재처리

같은 Issue에 다시 `Issue #번호 구현하라`를 입력하면 담당자 AI는 Skill을 형식적으로 다시 읽지 않는다.
실제 GitHub Issue, Human 답변, Issue 댓글의 최종 계약, 현재 `status:*` Label,
현재 브랜치와 작업 트리, 계약 변경 여부를 다시 확인한 뒤 Issue 단계의 다음 작업부터 재개한다.
연결 PR이 있으면 Issue 명령으로 Diff·리뷰·댓글을 검토하거나 수정하지 않고
`PR #번호 검토하라`가 필요하다고 보고한다.

Issue 또는 계약이 크게 변경되어 필요한 기준 문서가 달라진 경우에만 Skill을 다시 확인해 문서를 재선택한다.

Human이 Issue 본문에 답변을 작성한 뒤에는 같은 명령을 다시 입력한다.
Human이 PR 재검토, 외부 Review 반영 또는 추가 수정 검토를 명시적으로 요청할 때는 `PR #번호 검토하라`를 사용한다.

별도의 다른 팀원 AI 리뷰는 워크플로우 단계나 완료 조건으로 사용하지 않는다. PR에 리뷰·댓글이 등록되면 작성 주체와 관계없이 담당자 AI가 실제 코드 근거를 확인해 반영 여부를 판단한다.

## 4. `Issue #번호 구현하라` 상태별 동작

현재 실행 상태는 Human 답변이 포함된 Issue 본문이 아니라 GitHub Label로 기록한다.
실제 상태의 유일한 기준은 `status:*` Label이며, Issue 본문의 상태 문자열은 현재 실행 상태로 사용하지 않는다.
상태를 바꿀 때는 기존 `status:*` Label을 모두 제거한 뒤 새 상태 Label 하나만 적용한다.

| Label | 의미 |
|---|---|
| `status:human-answer-required` | Human 답변 누락·불명확, 문서 충돌, 정책 결정 또는 범위 변경으로 구현을 중단한 상태 |
| `status:in-progress` | Human 답변 검토와 계약 확인을 마치고 구현·검증·PR 갱신을 진행하는 상태 |
| `status:final-human-review` | 최신 Head의 구현·검증·AI 검토를 마쳐 Human 최종 리뷰를 기다리는 상태 |

Issue 본문은 목적·범위·완료 조건·Human 질문과 답변을 보존한다.
AI 답변 검토·보완 설명·최종 계약·구현 착수와 완료 기록은 별도 Issue 댓글에 남긴다.

| 현재 조건 | 담당자 AI 동작 |
|---|---|
| 필수 Human 답변이 없음 | Issue·문서·코드를 분석하고 최초 Human 질문을 Issue 본문에 기록한 뒤 `status:human-answer-required`를 적용하고 중단 |
| Human 답변이 모두 있음 | 답변을 문서·코드와 대조하고, 질문별 검토·보완 설명·최종 계약·구현 진행 또는 중단 판정을 대화창과 Issue 댓글에 기록 |
| 답변·계약에 충돌이나 미결정 사항이 없음 | 같은 `Issue #번호 구현하라` 실행에서 `status:in-progress`를 적용하고 구현·테스트·Diff 검토·Commit·Push·Draft PR 생성 또는 최초 갱신 진행 |
| 정책·API·DB 재결정, 답변 불명확, 보안·권한·상태 계약 불명확, 범위 변경이 필요함 | `status:human-answer-required`를 적용하고 구현 중 새로 발생한 추가 Human 질문을 댓글에 기록한 뒤 중단 |
| 연결된 PR이 존재함 | Issue 명령에서는 PR 검토·수정·Push를 수행하지 않고 `PR #번호 검토하라`가 필요하다고 보고 |

Human이 답변을 작성한 뒤 `Issue #번호 구현하라`를 입력하는 것은 답변 검토와,
충돌이 없을 때 같은 실행의 구현 진행을 요청하는 신호다. 별도의 `AI_FINALIZED → HUMAN_APPROVED`
명령 왕복은 사용하지 않는다. Ready 전환, Approve와 Merge는 계속 Human 책임이다.

## 5. V3 Sprint Mode PR 검토와 수동 진입 명령

### 자동 독립 Review

V3 Sprint Mode에서는 Draft PR 생성 직후 또는 담당 구현 AI의 새 Push 직후 다음 흐름으로 최신 Head를 독립 검토한다.

```text
Draft PR 생성 또는 새 Push
→ 담당 구현 AI가 최신 Issue 계약 / Head / Diff / 검증 / Evidence를 다시 읽음
→ `bobfull-pr-review` Skill 기반 독립 Review
→ BLOCKER/MAJOR가 있으면 수정·재검증
→ 없으면 Human 최종 Merge 대기
```

자동 Review는 기존 Review 결과나 이전 SHA를 재사용하지 않는다. BLOCKER와 MAJOR만 Merge를 차단하며,
Before/After Evidence, `NOT_APPLICABLE` 허용 기준, 최신 Head 재검토 기준은
`skills/bobfull-pr-review/SKILL.md`와 `docs/evidence/v3/README.md`를 따른다.

### `PR #번호 검토하라` 수동 진입

```text
PR #번호 검토하라
```

이 명령은 초기 자동 Review를 시작하기 위한 필수 관문이 아니라, Human이 PR 재검토, 외부 Review 반영,
추가 수정 검토를 명시적으로 요청할 때 사용하는 수동 PR 진입 명령이다. 담당자 AI는 PR 번호로 연결된 모든
Issue, 각 Issue 댓글의 최종 계약, 현재 `status:*` Label, 최신 Head, PR 답변·리뷰·댓글과 로컬 상태를
확인한다. PR 답변 또는 Human 리뷰 작성 후 `Issue #번호 구현하라`를 다시 입력하도록 요구하지 않는다.

PR을 읽고 보고만 할 때는 기존 Label을 유지한다. 실제 파일 수정을 시작할 때만 연결된 모든 Issue에서
기존 `status:*` Label을 제거하고 `status:in-progress` 하나를 적용한다. 수정·검증·Push·최신 Head
자체 검토가 끝나면 연결된 모든 Issue에서 `status:final-human-review` 하나만 적용한다.

## 6. Human 경계와 담당자 AI 검토

### V3 Sprint Mode의 Human 책임

V3 Sprint Mode에서 별도 GitHub Human Approve 1개 이상이나 다른 팀원의 형식적 Approve 대기는 필수
Merge Gate가 아니다. 그러나 다음 판단과 실행은 계속 Human 책임이다.

- 정책 변경
- API 계약 변경
- DB·상태 모델 재결정
- 권한·보안 정책 결정
- 트랜잭션 경계 재결정
- Human 답변이 필요한 Issue 결정
- 최종 Merge

즉, Human 판단 책임을 없애는 것과 GitHub Approve를 필수 Merge Gate에서 제외하는 것은 다르다.
담당자 AI는 Human 판단이 필요한 사항을 자동 Review로 대체하거나, Approve 또는 Merge를 수행하지 않는다.

### Human 입력과 담당자 AI 검토

PR 담당자는 PR 본문의 `Human 이해도` 질문에 직접 답한다.

Human 이해도 질문은 신입 백엔드 기술면접 기본 개념 수준으로 작성하며, 강화 검토도 질문 난이도를 높이지 않는다. 질문 수·난이도·허용·금지·자기 검증·응답 처리의 상세 기준은 `docs/ai/ai-review-guide.md`를 따른다.

담당자 AI는 Human 답변을 최신 코드와 대조하고 다음 항목만 작성한다.

- `AI 답변 검토`: `일치 | 보완 필요 | 미작성`
- `AI 보완 설명`: 코드 흐름과 테스트 근거

담당자 AI는 Human 답변 원문을 대신 작성하거나 덮어쓰지 않는다.

PR 작성자가 아닌 Human 리뷰어는 PR 본문 마지막의 PR별 `Human Review Checklist`를 참고하거나 복사해 PR 댓글로 체크 여부와 의견을 직접 작성할 수 있다. V3 Sprint Mode에서는 이 체크리스트와 GitHub Approve를 필수 Merge Gate로 사용하지 않는다. 리뷰어와 리뷰 시각은 GitHub 댓글 메타데이터를 사용하며, 체크리스트 댓글에 기준 Head SHA를 기록하거나 추정하지 않는다.

새 Commit 이후 정식 Approve의 유효성은 GitHub Branch Ruleset의 `dismiss_stale_reviews_on_push: true` 설정으로 관리한다. V3 Sprint Mode의 자동 독립 Review와 수동 `PR #번호 검토하라` 모두 최신 Diff를 다시 검토한다.

PR에 등록된 리뷰·댓글은 공식 선행 단계가 아니다. 담당자 AI는 작성 주체와 관계없이 다음처럼 처리한다.

- 실제 코드 근거가 있는 범위 안 결함: 수정·재검증
- 설명 또는 확인 요청: 답변 또는 확인 결과 정리
- 정책·API·DB·권한·트랜잭션 재결정: Human 판단 요청
- 근거 없음·범위 밖 제안: 반영하지 않고 이유 기록

## 7. 문서 라우팅

| 작업 | 기준 문서 |
|---|---|
| 프로젝트 정책·버전·역할 | `docs/product/project-context.md` |
| 실제 HTTP API 계약 | `docs/api/bobfull-api-spec-complete.md` |
| 관계형 데이터 모델·정합성 제약 | `docs/data/erd.md` |
| 논리 구성 요소·책임 경계 | `docs/architecture/architecture.md` |
| 중요한 기술·구조 결정 기록 | `docs/architecture/adr/README.md` |
| AI 전체 절차 | `docs/ai/ai-workflow.md` |
| 담당자 AI 실행 | `docs/ai/ai-implementation-guide.md` |
| 담당자 AI PR 검토·리뷰 반영 | `docs/ai/ai-review-guide.md` |
| V3 자동 독립 Review·Evidence Gate | `skills/bobfull-pr-review/SKILL.md`, `docs/evidence/v3/README.md` |
| Human 이해도 질문 난이도·생성 | `docs/ai/ai-review-guide.md` |
| 코드 작성 | `docs/engineering/code-convention.md` |
| 테스트·증거 | `docs/engineering/test-convention.md` |
| Git·PR·Merge | `docs/engineering/github-rules.md` |
| 도메인 영향 | `docs/architecture/domain-dependencies.md` |
| Issue 제목 | `docs/engineering/issue-title-rules.md` |

API 계약은 `docs/api/bobfull-api-spec-complete.md`, 프로젝트 정책·버전·역할은 `docs/product/project-context.md`, 데이터 모델과 저장값·계산값 구분은 `docs/data/erd.md`를 기준으로 한다. 세 문서가 충돌하면 임의로 선택하거나 덮어쓰지 않고 중단한다.

API 변경은 API 명세와 `PROJECT_CONTEXT`, ERD, 영향 문서의 동기화 범위를 확인한다. 도메인 정책 변경은 API 명세·PROJECT_CONTEXT·ERD와 영향 문서를 함께 검토하고, 데이터 모델 변경은 ERD와 관련 API의 Request·Response·계산값·정합성 제약을 함께 검토한다.

## 8. 필수 경계

- 한 번에 하나의 Issue만 처리한다.
- AI가 Human 답변이나 Human 리뷰를 대신 작성한 것처럼 표시하지 않는다.
- AI 보완 내용은 반드시 `AI 보완 설명`으로 구분한다.
- Issue 범위 밖 기능과 불필요한 리팩터링을 추가하지 않는다.
- 정책·API·DB·상태·권한·트랜잭션을 임의로 결정하지 않는다.
- 필요한 Human 답변 검토·최종 계약 확인·`status:in-progress` 기록 없이 구현하지 않는다.
- 상태 변경 전 기존 `status:*` Label을 제거하고 새 상태 Label 하나만 적용한다.
- 실행하지 않은 테스트를 `PASS`로 기록하지 않는다.
- 전체 build가 실패하면 완료로 표현하지 않는다.
- 비밀정보와 실제 개인정보를 입력·Commit·PR에 포함하지 않는다.
- V3 Sprint Mode에서는 별도 GitHub Human Approve를 필수 Merge Gate로 요구하지 않지만, 담당자 AI의 PR 검토는 Human의 정책 판단이나 최종 Merge를 대체하지 않는다.
- 다른 팀원 AI 리뷰의 존재 여부를 작업 시작·수정·Merge 조건으로 사용하지 않는다.
- AI는 Approve와 Merge를 수행하지 않는다.
- API Response의 계산값을 근거 없이 DB 컬럼으로 중복 저장하지 않는다. 저장이 필요하면 갱신 책임·정합성·동시성 전략을 Human과 별도 결정한다.
- `READY` Payment의 임시 좌석 선점·만료 정책을 바꾸거나, `Settlement`, `SeatHold`, `WebhookEvent` 엔티티를 추가하려면 Human 승인과 기준 문서 반영이 필요하다.

## 9. 파일 수정 안전 규칙

- 기존 문서 수정 요청은 별도 `SUMMARY`, `UPDATED`, `FINAL` 파일을 만들지 않고 기존 파일을 직접 수정한다.
- 사용자의 사전 승인 없이 임시 폴더, 압축·Base64 파일, trigger 파일, 일회성 GitHub Actions Workflow를 저장소에 추가하지 않는다.
- 완료 보고 전 대상 파일을 다시 읽고, PR 최종 변경 목록에 요청하지 않은 파일이 없는지 확인한다.
- 도구 제한으로 정상 수정이 불가능하면 임의 우회하지 말고 작업을 중단하여 사용자에게 보고한다.

## 10. 즉시 중단 조건

- 확정 문서·Issue·코드가 충돌함
- 필요한 Human 답변이 모호하거나 핵심 미결정 사항이 남음
- Human 답변 검토와 Issue 댓글의 최종 계약 기록이 없거나, 그 이후 계약이 변경됨
- 다른 담당자의 계약 변경이 필요함
- 새로운 정책·API·DB·인프라 결정이 필요함
- 데이터 정합성·권한·보안·손실 위험이 발견됨
- 핵심 검증 환경이나 권한이 없음
- Issue 범위 밖 변경이 필요함

위 중단 조건은 담당자 AI가 임의 구현·수정을 멈추는 기준이다. 이미 존재하는 PR은 확인 가능한 최신 Diff까지 검토하고 위험과 필요한 Human 결정을 보고한다.
