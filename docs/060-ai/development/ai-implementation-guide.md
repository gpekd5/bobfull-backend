# BobFull 담당자 AI 구현 가이드

이 문서는 Issue 단계에서 계약이 확정되고 `status:in-progress`가 적용된 뒤의 구현·검증만 다룬다. 공통 안전·상태·Human 책임은 이미 적용한 `AGENTS.md`를 유지한다.

## 1. 구현 입력

구현 전 다음이 있어야 한다.

- Issue 최신 계약과 제외 범위
- 대상 Issue 전용 브랜치와 깨끗하거나 설명 가능한 작업 트리
- 변경 대상에 필요한 정책·API·DB·Architecture 원본
- 관련 코드와 기존 테스트
- 구조 리팩터링의 Before/After·동작 보존을 기록하거나 개선 효과를 주장한다면 해당 Evidence 측정 계약

정책·API·DB·상태·권한·보안·트랜잭션 결정이 남아 있으면 `AGENTS.md`의 Human 경계에 따라 중단한다.

## 2. 필요한 문서만 추가

| 구현 내용 | 추가 문서 |
|---|---|
| Branch·Commit·PR 규칙 | `docs/050-engineering/github-rules.md` |
| 코드 작성 | `docs/050-engineering/code-convention.md`, 필요 시 `common-package-guide.md` |
| 테스트 작성·결과 기록 | `docs/050-engineering/test-convention.md` |
| 구조 리팩터링·동작 보존 Evidence | `docs/110-records/evidence/refactoring/README.md`와 해당 Issue Evidence |
| 개선 효과 주장 | `docs/110-records/evidence/v3/README.md`와 해당 Issue Evidence |
| PR 생성·본문 갱신 | `docs/060-ai/tasks/bobfull-pr-explain/TASK.md`, 최신 PR template |
| Draft PR 생성 또는 Push 뒤 Review | `docs/060-ai/tasks/bobfull-pr-review/TASK.md` |

PR 문서는 PR 단계 직전에 읽고, Evidence 문서는 구조 리팩터링 기록 또는 개선 효과 주장에 해당할 때 선택해 읽는다. 관련 없는 ADR·API 상세·과거 Evidence 전체를 선행 로딩하지 않는다.

## 3. 구현 순서

1. 브랜치와 작업 트리를 다시 확인한다.
2. Issue 완료 조건을 코드·테스트·문서 변경으로 연결한다.
3. 구조 리팩터링이면 변경 전 구조와 동작 보존 Guardrail을 측정하고, Enhancement면 Before 문제나 기준값을 재현한다.
4. Issue 범위 안에서 코드·테스트·필요 문서를 수정한다.
5. 변경 기능 관련 테스트를 실행한다.
6. 구조 리팩터링 또는 Enhancement면 같은 조건으로 After를 재검증하고 정합성 회귀를 확인한다.
7. 핵심 기능을 직접 검증한다.
8. 변경 범위에 필요한 전체 build를 실행한다.
9. base 대비 Diff와 변경 파일을 검토한다.
10. Issue 관련 변경만 Commit·Push한다.
11. PR Explain Task Guide로 develop 대상 Draft PR을 작성한다.
12. Draft PR 생성 또는 새 Push 직후 PR Review Task Guide로 최신 Head를 검토한다.

문서·설정 작업처럼 build나 런타임 검증이 의미 없으면 `NOT_RUN` 또는 `NOT_APPLICABLE` 이유와 대신 실행한 정적 검증을 기록한다.

## 4. 테스트와 직접 검증

테스트 설계·이름·증거 기록은 `docs/050-engineering/test-convention.md`를 기준으로 한다.

- HTTP/API: Postman, curl 또는 동등한 실제 요청
- 결제·예약·환불: 핵심 상태 변화와 외부·내부 결과
- Scheduler·Event·Consumer: 실행 가능한 테스트·트리거·로그
- 문서·설정: 링크·렌더링·설정 적용 등 직접 관련된 정적 검증

Issue 완료 조건과 실제 실패 위험을 우선한다. 범위 밖 시나리오를 무제한으로 늘리지 않고, 실행하지 않은 검증을 `PASS`로 기록하지 않는다.

## 5. Evidence

구조 리팩터링의 Before/After·동작 보존을 기록하거나 개선 효과를 주장하는 작업에 Evidence 표준을 적용한다.

- Before와 After의 환경·데이터·부하 조건을 가능한 한 맞춘다.
- 기능·상태·멱등성·정합성 회귀를 함께 확인한다.
- Commit SHA, 사용한 Fake·Mock·Sandbox와 검증 한계를 기록한다.
- 실제 측정 범위보다 넓은 개선을 주장하지 않는다.
- 대용량 원본 로그를 무조건 Commit하지 않는다.

- 구조 변경과 기존 동작 보존: `docs/110-records/evidence/refactoring/README.md`
- 성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI 개선 효과: `docs/110-records/evidence/v3/README.md`

## 6. PR 인계

구현 단계가 끝나면 다음 자료만 PR 단계로 넘긴다.

- 최신 Issue 계약과 실제 Diff
- 테스트·build·직접 검증 결과와 미실행 근거
- 해당하면 Before/After Evidence와 한계
- 남은 위험·기술부채·범위 밖 항목

PR 본문 구조를 이 문서에 복제하지 않는다. `docs/060-ai/tasks/bobfull-pr-explain/TASK.md`와 최신 `.github/pull_request_template.md`를 사용한다.

AI Review 기준·등급·댓글 형식을 이 문서에 복제하지 않는다. Draft PR 생성 또는 새 Push 뒤 `docs/060-ai/tasks/bobfull-pr-review/TASK.md`를 실행한다.

현재 Merge Gate는 `.github/pull_request_template.md`를 확인한다. 담당자 AI는 Approve 또는 Merge를 수행하지 않는다.
