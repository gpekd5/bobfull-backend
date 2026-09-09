---
name: bobfull-pr-explain
description: BobFull V3 Sprint Draft PR에서 실제 Issue, Diff, build, 직접 검증, 필요한 Before/After Evidence를 바탕으로 팀원이 빠르게 이해할 수 있는 PR 설명을 작성한다.
---

# BobFull V3 Sprint PR Explain

## 역할

이 Skill은 **PR을 빠르게 이해하게 만드는 설명**을 담당한다.

코드 결함 리뷰는 Draft PR 생성 직후 같은 담당 구현 AI가 `skills/bobfull-pr-review/SKILL.md`를 적용해 독립 리뷰 패스로 수행한다.

현재 V3 Sprint Mode에서는 설명이 길어지는 것보다 다음이 명확한지가 중요하다.

```text
무엇을 왜 바꿨는가
핵심 흐름이 무엇인가
중요한 개념·트레이드오프가 무엇인가
전체 build와 직접 검증이 통과했는가
고도화 PR이면 무엇이 Before보다 어떻게 달라졌는가
그 차이를 뒷받침하는 Evidence가 어디에 있는가
담당 구현 AI Review에 BLOCKER/MAJOR가 남았는가
```

## 사용 시점

- 구현 완료 후 Draft PR 생성 전
- 새 Commit으로 실제 흐름·검증·Evidence 상태가 바뀐 뒤
- Merge 전 최신 상태 갱신

## 필수 입력

1. 연결 Issue의 최신 계약과 범위
2. 최신 `.github/pull_request_template.md`
3. base 대비 실제 Diff
4. 관련 테스트 결과
5. 전체 build 결과
6. 변경 핵심 기능 직접 검증 결과
7. 고도화 PR이면 Before/After Evidence와 비교 조건·한계
8. 최신 담당 구현 AI Review 결과가 있으면 그 결과

실행하지 않은 검증이나 Evidence는 PASS로 쓰지 않는다.

## 작성 절차

1. `한 줄 요약`에서 무엇을 왜 바꿨는지 바로 설명한다.
2. `PR 이해 요약`에서 핵심 실행 흐름과 중요한 기술 개념만 정리한다.
3. Mermaid는 실제 흐름 이해에 도움이 되는 기능 PR에서만 작성한다.
4. 단순 문서·설정·CRUD는 불필요한 개념·트러블슈팅·다이어그램·성능 수치를 억지로 만들지 않는다.
5. `상세 변경 및 검증`에서 예외·실패·중복·경계, 실제 트레이드오프, 제한사항, Merge 차단 위험과 비차단 후속 항목을 분리한다.
6. 트레이드오프는 실제 선택에서 얻은 것과 포기한 것이 있을 때만 작성하고, 단순 변경이면 `해당 없음`과 이유를 적는다.
7. 고도화 PR이면 `Before / After Evidence`에 핵심 지표·현상만 요약하고 원본 Evidence 경로를 연결한다.
8. Before/After가 의미 없는 PR은 `NOT_APPLICABLE`과 이유를 적는다.
9. `V3 Sprint 필수 검증`에 관련 테스트·전체 build·핵심 기능 직접 검증·Before/After Evidence·담당 구현 AI Review 상태를 기록한다.
10. `BLOCKER`·`MAJOR`·`FAIL`은 접힌 영역에만 숨기지 않는다.
11. MINOR·SUGGESTION은 Merge를 막지 않는 후속 항목으로 분리한다.
12. Human 이해도는 기본 0개 / 강화 정확히 3개로 유지한다.

## Before / After Evidence 작성 기준

PR 본문은 원본 실험 보고서가 아니라 **핵심 결론과 근거 위치를 빠르게 보는 곳**이다.

작성할 내용:

```text
Evidence 판정
Evidence 경로
Before/After Commit SHA
동일 조건 여부
핵심 지표 또는 핵심 장애 현상
정합성 회귀 결과
검증 한계
```

하지 말 것:

- 실행하지 않은 숫자 채우기
- 다른 환경의 수치를 직접 개선율로 비교하기
- 실제 검증 범위보다 넓게 `완전 해결`, `유실 0 보장`, `무중단 보장`이라고 표현하기
- 대용량 로그 전체를 PR 본문에 붙이기

공통 저장 기준은 `docs/110-records/evidence/v3/README.md`를 따른다.

## 핵심 기능 직접 검증

기능 PR은 사용자가 실제로 원하는 동작을 확인한다.

- HTTP/API: Postman, curl 또는 동등한 실제 요청
- 결제·예약·환불: 핵심 상태 전이와 결과
- Event/Scheduler/Consumer: 직접 트리거·테스트·로그
- 문서/설정: 정적 검사 또는 적용 결과

범위 밖 시나리오를 무한히 확장하지 않는다.

## 담당 구현 AI Review와의 경계

```text
담당 구현 AI — 구현자 역할
→ Before 재현·구현·테스트·After 재검증
→ Evidence 기록·build·직접 검증
→ PR Explain
→ Draft PR 생성

같은 담당 구현 AI — 리뷰어 역할
→ Issue/Head/Diff/검증/Evidence 근거 최신 재수집
→ skills/bobfull-pr-review/SKILL.md 적용
→ 중요도 순 PR Review 댓글
→ BLOCKER/MAJOR면 수정·Push·즉시 재리뷰
```

별도의 GitHub Copilot이나 외부 리뷰 AI를 필수로 사용하지 않는다.
최초 리뷰를 위해 Human이 추가 명령을 입력할 필요가 없다.

V3 Sprint Mode에서:

- BLOCKER/MAJOR → Merge 차단
- MINOR/SUGGESTION → 기록 후 Merge 가능

## Human 이해도

### 기본

질문 0개.

### 강화

정확히 3개.

1. 핵심 실행 흐름과 주요 분기
2. 중요한 기술 개념과 실제 적용 이유
3. 설계 선택 이유, 주요 실패 처리와 남은 한계

코드 암기 문제가 아니라 기능 설명 능력을 확인한다.

## 목적

PR Explain은 포트폴리오 문서가 아니라 **스퍼트 중 팀원이 빠르게 읽고 핵심을 이해하는 작업 문서**다.

Evidence 원본은 별도 문서에 두고 PR에서는 결과와 경로를 짧게 연결한다.
필요 이상으로 길게 만들지 않는다.
