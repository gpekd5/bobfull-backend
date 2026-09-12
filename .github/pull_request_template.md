## 한 줄 요약

무엇을:
왜:
기술부채(있다면):
의도부채(있다면, Issue 논의와 달라진 부분):

## 관련 Issue

- Closes #
- 검토 수준: `기본 | 강화`
- 운영 모드: `V3 Sprint Mode`

<!-- Draft PR 생성 직후 해당 PR을 구현한 담당 AI가 별도 Human 명령 없이 skills/bobfull-pr-review/SKILL.md를 적용해 최신 Head를 독립 리뷰 패스로 검토하고 PR 댓글을 남깁니다. GitHub Copilot은 필수 구성요소가 아닙니다. -->

## PR 이해 요약

### 쉬운 설명

<!-- 처음 보는 팀원이 3~5문장으로 무엇을 왜 바꿨는지 이해할 수 있게 작성합니다. -->

-

### 주요 실행 흐름

<!-- 실제 핵심 흐름과 중요한 분기만 작성합니다. -->

1.
2.
3.

### Mermaid 시각화

<!-- 의미 있는 실행 흐름이 있을 때만 작성합니다. 단순 문서·설정·CRUD면 `해당 없음`과 이유를 작성합니다. -->

해당 없음:

### 주요 개념

<!-- 실제 PR 이해에 필요한 개념만 작성합니다. -->

| 개념 | 쉽게 말하면 | 이 PR에서 왜 필요한가 |
|---|---|---|
|  |  |  |

### 핵심 트러블슈팅

<!-- 실제 의미 있는 문제 해결이 있었을 때만 작성합니다. 없으면 `해당 없음`과 이유를 작성합니다. -->

해당 없음:

### 코드 읽는 순서

1.
2.
3.

## 상세 변경 및 검증

- 현재 Merge를 막는 위험(BLOCKER·MAJOR·FAIL):
- Merge를 막지 않는 후속 항목(MINOR·SUGGESTION·기술부채):

<details>
<summary>상세 변경 및 검증 펼치기</summary>

### 주요 변경

-

### 예외·실패·중복·경계 상황

-

### 트레이드오프

<!-- 실제 설계 선택에서 얻은 것과 포기한 것이 있을 때만 작성합니다. 단순 변경이면 `해당 없음`과 이유를 작성합니다. -->

-

### Refactor Before / After

- 적용 여부: `해당 | N/A`

#### Before

- 변경 전 구조/책임/의존성:

#### After

- 변경 후 구조/책임/의존성:

#### 변경하지 않은 것

- API / DB / 비즈니스 동작 / Transaction 등:

#### 후속 설계 부채

- 이번 PR에서 발견했지만 의도적으로 건드리지 않은 항목:

### 현재 제한사항과 후속 개선

-

### 제외 범위

-

### 추가·수정 테스트

| 테스트 클래스·파일 | 검증 시나리오 | 이 테스트가 보장하는 것 |
|---|---|---|
|  |  |  |

### 완료 조건·검증 결과

| Issue 완료 조건 | 구현 위치 | 검증 증거 | 결과 |
|---|---|---|---|
|  |  |  | `PASS | FAIL | NOT_RUN` |

</details>

## Before / After Evidence

<!--
Refactor Learning Mode의 구조 변경·동작 보존 Evidence는 작성합니다.
성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI 등 개선 효과를 주장하는 PR은 작성합니다.
단순 CRUD·문서·DTO처럼 Before/After 비교가 의미 없으면 `NOT_APPLICABLE`과 이유를 작성합니다.
실제 측정 전 임의 수치를 채우지 않습니다.
구조 리팩터링 기준: docs/110-records/evidence/refactoring/README.md
개선 효과 기준: docs/110-records/evidence/v3/README.md
-->

### 측정 계약

<!-- Issue/Evidence에 정의된 대표 지표를 요약합니다. 정량 KPI가 의미 없는 신뢰성 문제는 검증 가능한 현상을 적습니다. -->

- Primary KPI:
- Secondary KPI:
- Guardrail:

### Evidence 결과

- Evidence 판정: `PASS | FAIL | NOT_APPLICABLE`
- Evidence 경로:
- Before Commit SHA:
- After Commit SHA:
- 동일 조건 여부:
- 측정·재현 환경:
- 검증 한계:

| 핵심 지표·현상 | Before | After | 판정 |
|---|---|---|---|
|  |  |  | `PASS | FAIL | N/A` |

### 정합성 회귀 확인

<!-- 구조 변경 또는 개선 뒤 기존 기능·상태·멱등성·정합성이 깨지지 않았는지 기록합니다. -->

-

## 필수 검증

<!-- 기능 PR은 아래 항목을 우선합니다. 문서·설정 전용이면 해당하지 않는 항목에 NOT_RUN/N/A 이유를 적습니다. -->

| Merge Gate | 실행 명령·환경 | 결과 | 증거·한계 |
|---|---|---|---|
| 관련 테스트 |  | `PASS | FAIL | NOT_RUN` |  |
| 전체 build |  | `PASS | FAIL | NOT_RUN` |  |
| 핵심 기능 직접 검증 | Postman/curl/직접 트리거 등 | `PASS | FAIL | NOT_RUN` |  |
| Before/After Evidence | `docs/110-records/evidence/refactoring/...`, `docs/110-records/evidence/v3/...` 또는 N/A 근거 | `PASS | FAIL | NOT_APPLICABLE` |  |
| 담당 구현 AI Review | PR Conversation 댓글 | `MERGEABLE | BLOCK | 미실행` |  |

- 최신 검증 Commit SHA:
- 미해결 BLOCKER:
- 미해결 MAJOR:
- Human 결정 필요 사항:
- Merge를 막지 않는 MINOR/SUGGESTION:

## Human 이해 확인

### Human 이해도

<!-- 기본: 질문 0개, 아래 문구 유지. 강화: 아래 문구를 제거하고 정확히 3문항을 삽입합니다. Refactor Learning Mode에서 Issue 단계의 이해 질문을 완료했다면 PR에서 같은 질문을 반복하지 않습니다. -->

해당 없음: 기본 검토

<!-- 강화 PR 3문항 축
1. 핵심 실행 흐름과 주요 분기
2. 가장 중요한 기술 개념과 실제 적용 이유
3. 설계 선택 이유, 주요 실패 처리와 남은 한계
-->

### 담당 구현 AI Review·반영 기록

- Review Skill: `skills/bobfull-pr-review/SKILL.md`
- 최신 Review 기준 Head:
- 최신 판정: `MERGEABLE | BLOCK | 미실행`
- 최신 PR Review 댓글:
- BLOCKER/MAJOR 반영 내용:
- MINOR/SUGGESTION 후속 처리:
- 리뷰 후 재실행 검증:

### Human 이해 Checklist

<!-- 별도 리뷰어 Approve Gate가 아니라 담당자와 팀원이 PR을 빠르게 이해하기 위한 기준입니다. -->

- [ ] 이 PR이 무엇을 왜 변경하는지 이해했다.
- [ ] 기본 실행 흐름과 중요한 분기를 이해했다.
- [ ] 중요한 기술 개념과 주요 트레이드오프가 있다면 어디에 왜 적용됐는지 이해했다.
- [ ] 전체 build·직접 검증·필요한 Before/After Evidence·담당 구현 AI Review 결과와 남은 위험을 확인했다.

## Merge Gate

<!-- 필수 Human Approve: 0명 -->

- [ ] 변경 범위에 필요한 테스트·build·직접 검증 `PASS` 또는 미실행 근거 명확
- [ ] 최신 Head 기준 담당 구현 AI Review 완료 및 미해결 `BLOCKER / MAJOR` 없음
- [ ] Human 결정 필요 사항 없음
- [ ] 리팩토링이면 기존 동작 유지 확인, 개선 효과를 주장하면 Before / After Evidence 확인

`MINOR`와 `SUGGESTION`은 기록 후 Merge를 막지 않습니다.
