# GitHub Rules

## 1. 브랜치 구조

| 브랜치 | 역할 | 병합 대상 |
|---|---|---|
| `main` | 배포 가능한 안정 버전 관리 | - |
| `develop` | 개발 완료 기능 통합 및 검증 | `main` |
| `feature/*` | 새로운 기능 개발 | `develop` |

### 작업 흐름

1. `develop` 브랜치를 기준으로 `feature/*` 브랜치를 생성한다.
2. `feature/*` 브랜치에서 기능을 개발한다.
3. 개발 완료 후 `develop` 브랜치로 PR을 올린다.
4. 팀원이 코드 리뷰를 진행한다.
5. 최소 2명 이상의 Approve를 받은 후 PR 작성자가 Merge한다.
6. `develop`에서 기능 통합 및 테스트를 진행한다.
7. 배포 가능한 상태가 되면 `develop`에서 `main`으로 PR을 올린다.
8. `main` 병합 후 배포를 진행한다.

```text
develop에서 feature 브랜치 생성
        ↓
feature 브랜치에서 기능 개발
        ↓
develop으로 PR 생성
        ↓
2명 이상 Approve
        ↓
PR 작성자가 Merge
        ↓
develop에서 통합 테스트
        ↓
develop → main PR
        ↓
main 병합 후 배포
```

### V3 Sprint Mode 예외와 현재 운영 정책

위 일반 흐름의 `최소 2명 Approve`는 V3 Sprint Mode를 적용하지 않는 PR의 기본 규칙이다. 현재 V3 Sprint Mode 대상 PR에는 다음 규칙을 우선 적용한다.

```text
Draft PR 생성 또는 담당 구현 AI의 새 Push
→ 담당 구현 AI가 최신 Issue 계약 / Head / Diff / 검증 / Evidence 재확인
→ bobfull-pr-review 기반 자동 독립 Review
→ BLOCKER/MAJOR면 수정·재검증·Push·최신 Head 재Review
→ 없으면 담당 Human의 최종 Merge 대기
```

- 별도 GitHub Human Approve 1개 이상이나 다른 팀원의 형식적 Approve 대기는 필수 Merge 조건이 아니다.
- `PR #번호 검토하라`는 자동 Review의 선행 조건이 아니라, Human이 재검토·외부 Review 반영·추가 수정 검토를 명시적으로 요청할 때 쓰는 수동 진입점이다.
- BLOCKER와 MAJOR만 Merge를 차단한다. MINOR와 SUGGESTION은 기록하되 단독으로 Merge를 막지 않는다.
- 자동 AI Review는 정책·API 계약·DB/상태 모델·권한/보안·트랜잭션 경계의 Human 판단이나 최종 Merge를 대체하지 않는다.
- 현재 Merge Gate는 `.github/pull_request_template.md`, 최신 Head AI Review와 중요도 판정은 `docs/060-ai/tasks/bobfull-pr-review/TASK.md`를 따른다.

### 초기 저장소 설정 예외

`develop` 브랜치가 아직 생성되지 않은 최초 설정 단계에서는 팀 규칙·AI 워크플로우·Issue/PR 템플릿 등 저장소 기반 문서를 `feature/*` 브랜치에서 작성하고 `main`으로 PR을 올릴 수 있다.

초기 설정 PR을 `main`에 Merge한 뒤 최신 `main`에서 `develop`을 생성한다. 이후 기능 개발부터는 일반 브랜치 흐름을 따른다.

```text
초기 설정 feature 브랜치
→ main PR
→ 리뷰·승인·Merge
→ 최신 main에서 develop 생성
→ 이후 feature/* → develop
```

### 브랜치 이름

```text
main
  develop
    ├── feature/member-signup
    ├── feature/auth-login
    ├── feature/restaurant-register
    ├── feature/table-manage
    ├── feature/timeslot-manage
    ├── feature/reservation-create
    ├── feature/payment-deposit
    ├── feature/refund-cancel
    ├── feature/visit-status-manage
    ├── feature/noshow-manage
    ├── feature/chat-reservation
    └── feature/deploy-aws
```

### 브랜치 네이밍 컨벤션

- 영어 소문자로 작성한다.
- 슬래시(`/`)와 하이픈(`-`)을 사용한다.
- `feature/domain-feature` 형식으로 작성한다.
- 너무 길지 않게 핵심 단어만 사용한다.
- 브랜치명에는 담당자 이름을 사용하지 않는다.
- 폐기된 정책이나 구현하지 않는 기능명을 사용하지 않는다.

## 2. 커밋 컨벤션

```text
feat: 제목
```

| 타입 | 설명 | 예시 |
|---|---|---|
| `feat` | 새로운 기능 추가 | `feat: 로그인 페이지 구현` |
| `fix` | 버그 수정 | `fix: 비밀번호 유효성 검사 오류 수정` |
| `docs` | 문서 수정 | `docs: API 명세서 업데이트` |
| `style` | 기능 변경 없는 코드 포맷팅 | `style: 들여쓰기 정리` |
| `refactor` | 기능 변경 없는 코드 리팩터링 | `refactor: 유저 서비스 함수 분리` |
| `test` | 테스트 코드 추가 또는 수정 | `test: 로그인 유닛 테스트 추가` |
| `chore` | 빌드 설정·패키지 관리 등 기타 작업 | `chore: eslint 설정 추가` |
| `remove` | 파일 또는 코드 삭제 | `remove: 사용하지 않는 컴포넌트 삭제` |
| `build` | Gradle 의존성 및 빌드 설정 변경 | - |
| `rename` | 파일·폴더 이동 또는 이름 변경 | - |

### 제목 규칙

- 커밋 타입은 영어로 작성한다.
- 작업 내용은 한국어로 작성한다.
- 50자 이내로 작성한다.
- 마침표를 붙이지 않는다.
- 명령문보다 현재형의 간결한 표현을 사용한다.
- 커밋 바디는 기본적으로 생략하며, 제목만으로 설명하기 어려운 경우에만 작성한다.

좋은 예시:

```text
feat: 회원가입 이메일 인증 기능 추가
```

나쁜 예시:

```text
수정함
ㅇㅇ
asdfasdf
고침
작업중
```

## 3. 코드 리뷰 규칙

- 리뷰 코멘트는 건설적으로 작성한다.
- 최소 2명 이상의 Approve를 받은 후 PR 작성자가 직접 Merge한다.
- 단, V3 Sprint Mode 대상 PR은 위 Approve 수 대신 `V3 Sprint Mode 예외와 현재 운영 정책`의 자동 독립 Review와 BLOCKER/MAJOR 차단 기준을 적용한다.
- 리뷰 의견이 있으면 반영하거나 답변한 후 Merge한다.
- 하나의 PR은 하나의 기능 또는 하나의 버그 수정에 집중한다.
- 서로 관련 없는 여러 도메인의 변경을 하나의 PR에 포함하지 않는다.
- PR 범위가 지나치게 커지면 기능을 분리하여 별도의 PR로 작성한다.
- 리뷰 코멘트에는 수정 요청 이유나 대안을 함께 작성한다.

## 4. Issue·PR 생성 규칙

### Issue 생성

- 새 Issue 제목은 `docs/050-engineering/issue-title-rules.md`의 범위·유형 규칙을 따른다.
- `blank_issues_enabled: false`는 GitHub 웹 UI에서 빈 Issue 생성을 막아 템플릿 사용을 유도하는 최소 가드레일이다. CLI·API·자동화 도구의 제목·본문 형식까지 검증하거나 완전히 강제하지는 않는다.
- AI는 `새 Issue 초안 작성하라`로 작업 성격에 맞는 Issue template의 전체 구조를 먼저 제시하며, Human이 `이 초안으로 Issue 생성하라`고 승인한 경우에만 생성한다. 일반 기능·문서·설정·단순 리팩토링 작업은 `.github/ISSUE_TEMPLATE/feature.md`를 사용하고, `docs/060-ai/workflow/ai-workflow.md`의 Refactor Learning Mode에 해당하는 의미 있는 리팩토링은 `.github/ISSUE_TEMPLATE/refactor.md`를 사용한다.

### Draft PR 생성

- 웹 UI에서는 자동으로 채워진 템플릿의 전체 섹션을 확인하고, 연결 Issue·실제 변경·검증 결과·Human 영역을 누락 없이 작성한 뒤 생성한다.
- `gh` CLI는 기본 자동 채움에 의존하지 않고 저장소 템플릿을 명시적인 시작 본문으로 사용한다.

```text
gh pr create --draft --base develop --template .github/pull_request_template.md
```

- `--fill`, `--fill-first`, `--fill-verbose`, `--body`, `--body-file`을 사용하더라도 최종 본문을 최신 `.github/pull_request_template.md`와 대조해 전체 섹션과 순서를 보존한다.
- GitHub API, Connector, Codex 등 AI·자동화 도구도 템플릿을 직접 읽고 동일한 섹션·순서의 본문을 명시적으로 전달한다. 도구의 자동 채움 여부는 규칙 준수 근거가 아니다.
- 기존 PR에 템플릿 일부 또는 전체가 누락되면 새 PR을 만들지 않고 최신 템플릿 구조로 본문을 복구한다. 기존 작성자의 유효한 설명과 Human 원문은 적절한 섹션으로 보존하며, 추정으로 검증 결과나 Human 답변·리뷰를 채우지 않는다.
- 템플릿 복구만으로 구현 완료나 Merge 가능으로 판단하지 않는다. V3 Sprint Mode에서는 Draft PR 생성 또는 새 Push 뒤 자동 독립 Review 전에, 일반 모드에서는 늦어도 Ready 전환·Human Approve 요청 전에 구조를 복구한다.
