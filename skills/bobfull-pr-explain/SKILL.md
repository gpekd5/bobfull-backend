---
name: bobfull-pr-explain
description: 최신 Issue, Diff와 검증 결과를 저장소 PR template에 맞춰 설명한다.
---

# BobFull PR Explain

## 역할

이 Skill은 PR 본문 작성·복구·최신화를 담당한다. 구현 절차나 AI Review 등급을 다시 정의하지 않는다.

## 사용 시점

- 구현 완료 후 Draft PR 생성 전
- 새 Commit으로 실제 흐름·검증·Evidence가 바뀐 뒤
- 기존 PR 본문이 template과 다르거나 Merge 전 최신화가 필요할 때

## 필수 입력

- 연결 Issue의 최신 계약과 범위
- 최신 `.github/pull_request_template.md`
- base 대비 실제 Diff
- 테스트·build·직접 검증 결과와 미실행 근거
- 개선 효과를 주장하면 해당 Before/After Evidence와 한계
- 최신 담당 구현 AI Review 결과가 있으면 그 결과

## 작성 원칙

1. 최신 PR template의 섹션과 순서를 유지한다.
2. 무엇을 왜 바꿨는지와 핵심 실행 흐름을 먼저 설명한다.
3. 실제 선택에서 얻은 것·포기한 것이 있을 때만 트레이드오프를 작성한다.
4. Mermaid, 개념, 트러블슈팅은 이해에 도움이 될 때만 사용한다.
5. 문서·설정·단순 CRUD에서 의미 없는 다이어그램이나 성능 수치를 만들지 않는다.
6. Issue 완료 조건을 구현 위치와 실제 검증 결과에 연결한다.
7. `BLOCKER`, `MAJOR`, `FAIL`은 접힌 영역에만 숨기지 않는다.
8. `MINOR`, `SUGGESTION`, 범위 밖 항목은 비차단 후속 내용으로 구분한다.
9. 실행하지 않은 검증은 `PASS`로 쓰지 않는다.

template의 필드 목록과 Merge Gate를 이 Skill에 복제하지 않는다. 작성할 때 최신 template을 직접 읽는다.

## Evidence

개선 효과를 주장할 때만 `docs/110-records/evidence/v3/README.md`와 해당 Issue Evidence를 읽는다.

PR에는 원본 전체가 아니라 다음만 요약한다.

- Evidence 판정과 경로
- Before/After SHA와 비교 조건
- 핵심 지표·현상과 정합성 결과
- 검증 범위와 한계

Before/After가 의미 없는 변경은 `NOT_APPLICABLE` 이유를 적는다. 실행하지 않은 숫자, 비교 불가능한 개선율, 검증 범위보다 넓은 보장을 작성하지 않는다.

## Human 이해도

질문 수와 작성 기준은 `docs/060-ai/ai-review-guide.md`를 따른다. Refactor Learning Mode에서 Issue 단계와 같은 질문을 반복하지 않는다.

## AI Review 인계

Draft PR 생성 또는 새 Push 직후 같은 담당 구현 AI가 `skills/bobfull-pr-review/SKILL.md`를 실행한다.

PR Explain은 결함 Review를 대신하지 않는다. 최신 Review 결과가 생기면 Head SHA, 판정, 댓글 링크와 재실행 검증을 PR 본문에 반영한다.
