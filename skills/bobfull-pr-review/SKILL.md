---
name: bobfull-pr-review
description: BobFull PR의 담당 구현 AI가 최신 Head를 독립적으로 검토하고 BLOCKER, MAJOR, MINOR, SUGGESTION 판정과 검증 근거를 PR 댓글로 남기는 기준이다.
---

# BobFull 담당 구현 AI Review

## 1. 역할

이 Skill은 담당 구현 AI가 구현 단계와 분리된 리뷰 패스로 최신 PR을 검토하는 단일 기준이다.
Merge Gate는 [PR template](../../.github/pull_request_template.md)을 따른다. Human 이해 질문의 작성·검토는 이 Skill의 범위가 아니다.

담당 구현 AI는 구현 당시의 기억이나 이전 Review 판정을 재사용하지 않고 GitHub의 최신 근거를 다시 읽는다.

## 2. 실행 시점

다음 시점에는 별도 Human 명령 없이 Review를 실행한다.

1. Draft PR 생성 직후
2. 담당 구현 AI가 기존 PR에 새 Commit을 Push한 직후
3. `BLOCKER` 또는 `MAJOR` 수정 후 Push한 직후
4. Merge 전 Head가 마지막 Review 기준 Head와 달라진 경우

Human이 `PR #번호 검토하라`고 요청하면 같은 기준으로 수동 재검토한다.

## 3. 최신 입력

Review를 시작할 때 다음을 GitHub의 현재 상태 기준으로 확인한다.

- 연결 Issue의 최신 계약, 댓글, `status:*` Label
- 최신 PR Head SHA와 base 대비 전체 Diff
- 관련 테스트, build, 직접 검증의 실제 실행 결과
- 개선 효과를 주장하면 Before/After Evidence와 비교 조건
- PR 본문과 실제 Diff, 검증, Evidence의 일치 여부
- 기존 Review와 댓글의 미해결 지적

Review는 항상 현재 Head 기준이다. 새 Push 뒤에는 이전 Head의 판정이나 PASS를 재사용하지 않는다.

## 4. 검토 우선순위

다음 순서로 실제 변경 범위에 해당하는 위험을 확인한다.

1. Issue의 핵심 요구와 완료 조건 위반
2. build 실패 또는 주요 런타임 흐름 파손
3. 데이터 손실, 중복 처리, 잘못된 상태 전이
4. 결제, 환불, 예약, 좌석, 인증, 권한 정합성 문제
5. Transaction, Rollback, Lock, 멱등성, 외부 I/O, Event 경계 오류
6. 필수 예외 또는 실패 처리 누락
7. 테스트, build, 직접 검증 기록과 실제 결과의 불일치
8. Before/After Evidence의 누락, 비교 불가능성, 과도한 주장
9. PR 설명과 실제 Diff 또는 Evidence의 모순

문서, 설정, 단순 CRUD처럼 해당하지 않는 변경에 고급 아키텍처 문제나 의미 없는 성능 측정을 요구하지 않는다.

## 5. Evidence 확인

개선 효과를 주장하는 PR은 [Evidence 기준](../../docs/110-records/evidence/v3/README.md)에 따라 다음을 확인한다.

- 주장: 무엇이 개선됐다고 하는가
- Before: 기존 문제나 기준값을 실제로 재현했는가
- After: 같은 조건에서 다시 검증했는가
- 정합성: 기존 기능, 상태, 멱등성이 유지되는가
- 추적성: Before/After SHA와 Evidence 경로가 있는가
- 한계: Mock, Sandbox, 로컬 등 검증 범위를 숨기지 않았는가
- 표현: 측정 범위보다 넓은 보장을 주장하지 않는가

핵심 개선 목적을 검증할 Evidence가 없으면 `MAJOR` 후보다. 결과를 허위로 기록하거나 실제 Evidence와
핵심 주장이 정면으로 모순되면 `BLOCKER`가 될 수 있다. 비교가 의미 없는 변경에서
`NOT_APPLICABLE` 근거가 타당하면 문제로 만들지 않는다.

## 6. 중요도와 판정

### BLOCKER

Merge를 금지한다.

- 권한 우회 또는 보안 문제
- 데이터 손실
- 중복 결제 또는 잘못된 환불
- 핵심 기능 완전 실패나 build 불가
- 핵심 계약 정면 위반
- 검증 결과 허위 기록 또는 실제 Evidence와 핵심 주장의 정면 모순

### MAJOR

Merge를 금지한다.

- 주요 요구사항 실패 또는 주요 런타임 오류
- 필수 실패 처리 누락
- 운영 핵심 흐름에서 잘못된 결과가 발생할 가능성이 큼
- 핵심 개선 주장을 뒷받침하는 필수 Before/After Evidence가 없음
- 서로 다른 조건을 같은 Before/After처럼 비교해 개선 완료를 결론냄

### MINOR

기록하되 단독으로 Merge를 막지 않는다.

- 핵심 기능을 깨지 않는 작은 품질 문제
- 추가 테스트 가치
- 명확성 또는 유지보수성 개선
- 핵심 결론을 바꾸지 않는 Evidence 표현 개선

### SUGGESTION

기록하되 단독으로 Merge를 막지 않는다.

- 선택적 리팩터링이나 최적화
- 후속 개선 아이디어

`BLOCKER` 또는 `MAJOR`가 하나라도 있으면 `BLOCK`, 둘 다 없으면 `MERGEABLE`이다.
`MERGEABLE`은 완벽함이나 Human Merge 결정을 의미하지 않는다.

## 7. PR 댓글 형식

각 Review 결과를 PR Conversation에 다음 형식으로 남긴다.

```markdown
## 담당 구현 AI Review

- 기준 Head: `<SHA>`
- 연결 Issue: `#번호`
- 검토 수준: `기본 | 강화`

### BLOCKER
- 없음 또는 실제 지적

### MAJOR
- 없음 또는 실제 지적

### MINOR
- 없음 또는 실제 지적

### SUGGESTION
- 없음 또는 실제 제안

### 판정
`BLOCK | MERGEABLE`

### 검증 근거
- 관련 테스트:
- 전체 build:
- 핵심 기능 직접 검증:
- Before/After Evidence: `PASS | FAIL | NOT_APPLICABLE`
- Evidence 경로:
- 비교 조건/한계:
- 남은 미검증 위험:
```

실제 지적은 `BLOCKER`, `MAJOR`, `MINOR`, `SUGGESTION` 순으로 적고 지적을 만들기 위해 범위를 부풀리지 않는다.

## 8. 검증과 후속 처리

- 실행한 검증만 결과를 기록한다. 실행하지 않은 검증을 `PASS`로 표시하지 않는다.
- 문서나 설정 변경처럼 테스트 또는 build가 의미 없으면 `NOT_RUN`이나 `NOT_APPLICABLE`과 이유를 적는다.
- HTTP/API 변경은 가능하면 Postman, curl 등 실제 호출 결과를 우선한다.
- `BLOCKER` 또는 `MAJOR`는 범위 안에서 수정, 재검증, Push 후 최신 Head를 다시 Review한다.
- `MINOR` 또는 `SUGGESTION`은 기록 후 Merge를 막지 않는다.
- 정책, API, DB, 상태, 권한, 보안, Transaction 재결정은 Human 판단을 요청한다.

담당 구현 AI는 Human 답변, Review Checklist, Approve, Merge를 대신하지 않는다. 최종 Merge는 Human 책임이다.
