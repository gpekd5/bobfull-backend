# BobFull AI 협업 워크플로우

## 1. 현재 운영 모드 — V3 Sprint Mode

V3 마무리 기간에는 **속도를 우선하되 치명적인 결함은 담당 구현 AI의 독립 리뷰 패스와 필수 검증으로 차단**한다.

이 모드의 원칙은 `검토를 없앤다`가 아니라 **Merge를 막는 기준을 핵심 위험으로 좁힌다**는 것이다.

### Merge를 막는 항목

- 전체 build 실패
- 변경한 핵심 기능의 직접 검증 실패
- 성능·신뢰성·동시성·인프라·캐시·이벤트·AI 등 **고도화 효과를 주장하지만 필요한 Before/After Evidence가 없거나 비교 조건이 불명확한 경우**
- 최신 담당 구현 AI Review의 미해결 `BLOCKER` 또는 `MAJOR`
- 정책·API·DB·상태·권한·트랜잭션처럼 Human 결정이 필요한 미확정 사항
- 강화 PR의 Human 이해도 3문항 미완료

### Merge를 막지 않는 항목

- `MINOR`
- `SUGGESTION`
- 범위 밖 리팩터링
- 추가하면 좋은 테스트
- 현재 기능에 직접 영향이 없는 코드 스타일 개선
- 후속 개선으로 남길 수 있는 기술부채
- Before/After 비교가 의미 없는 단순 변경에서 `NOT_APPLICABLE` 근거가 명확한 경우

이 항목들은 PR 또는 후속 Issue·트러블슈팅으로 기록하고 진행을 막지 않는다.

### 승인 정책

V3 Sprint Mode에서는 **필수 Human Approve 인원은 0명**으로 운영한다.

- 별도 리뷰어 승인을 기다리지 않는다.
- Human Review는 가능하면 수행하지만 Merge 필수 조건으로 두지 않는다.
- Merge는 담당 Human이 필수 검증과 담당 구현 AI Review 결과를 확인한 뒤 수행한다.
- 스퍼트 종료 후 승인 정책은 다시 평가한다.

## 2. V3에서도 유지하는 안전 규칙

Sprint Mode는 기존 안전 규칙을 폐기하지 않는다. 다음은 그대로 유지한다.

- `AGENTS.md`와 `docs/50-engineering/github-rules.md`의 브랜치 안전 규칙
- `main`, `master`, `develop` 직접 수정 금지
- 다른 Issue 작업 브랜치에서 새 작업 시작 금지
- 새 Issue 브랜치는 최신 `develop` 기준 생성
- 한 번에 하나의 Issue만 처리하고 Issue 범위 밖 변경 금지
- 실제 상태는 GitHub `status:*` Label 하나로 관리
- 실행하지 않은 테스트·build·직접 검증을 `PASS`로 기록하지 않음
- 비밀정보·개인정보를 Commit하지 않음
- 정책·API·DB·권한·트랜잭션 재결정은 Human에게 요청
- Approve와 Merge는 AI가 대신 수행하지 않음

새 Issue 초안·생성, 브랜치 명명, 테스트 컨벤션 등 세부 규칙은 기존 전용 문서를 따른다.

- `docs/50-engineering/issue-title-rules.md`
- `docs/50-engineering/github-rules.md`
- `docs/50-engineering/test-convention.md`
- `skills/bobfull-onboarding/SKILL.md`

Sprint Mode에서 바뀌는 것은 **Human 대기와 리뷰 깊이, Merge 차단 기준**이지 이 안전 규칙들이 아니다.

## 3. 역할

- 구현 담당 AI: Issue 계약에 따라 Before 재현·기준값 확보, 구현·테스트·After 재검증, Evidence 기록, PR 설명 작성·독립 리뷰 패스·리뷰 반영
- 담당자 Human: 실제 정책 판단, 강화 PR 이해도 답변, 최종 Merge 판단
- Human 리뷰어: 선택적 추가 리뷰. V3 Sprint Mode에서는 승인 대기 조건이 아님

GitHub Copilot Code Review나 별도 외부 리뷰 서비스는 필수 구성요소가 아니다.

## 4. 전체 흐름

```text
Issue 분석
→ 고도화 Issue면 Evidence 유형·Before/After 검증 계획 확인
→ 미결정 정책이 없으면 바로 구현 착수
→ status:in-progress
→ Before 재현 또는 기준값 확보
→ 구현·관련 테스트
→ 동일 조건 After 재검증
→ 정합성 회귀 검증
→ Evidence 기록
→ 핵심 기능 직접 검증
→ 전체 build
→ Diff 자체 검토
→ bobfull-pr-explain 적용
→ Draft PR 생성
→ 같은 담당 구현 AI가 즉시 독립 리뷰 패스로 전환
→ 최신 Issue/Head/Diff/검증/Evidence 근거 재수집
→ 중요도 순 PR Review 댓글 작성
→ BLOCKER/MAJOR면 수정·재검증·Push
→ 같은 담당 구현 AI가 최신 Head 즉시 재리뷰
→ 기본 PR: Human 이해도 0개
→ 강화 PR: Human 이해도 정확히 3개
→ Sprint Merge 전 확인 단계 수행
→ 담당 Human Merge
```

**최초 AI Review를 위해 `PR #번호 검토하라` 같은 추가 Human 명령을 요구하지 않는다.**
Draft PR 생성이 담당 구현 AI의 리뷰 단계 시작점이다.

## 5. Issue 단계 — 질문 병목 최소화

V3 Sprint Mode에서는 Issue 단계의 **학습용 Human 질문을 구현 착수 조건으로 사용하지 않는다.**

AI가 Issue·코드·확정 문서를 읽고 구현 가능한 수준이면 바로 계약을 정리하고 진행한다.

Human 질문은 다음처럼 실제 결정이 필요한 경우에만 한다.

- 정책이 둘 이상 가능하고 코드만으로 결정할 수 없음
- API·DB·상태 계약 변경
- 권한·금액·트랜잭션·보상 정책 재결정
- 다른 담당자 범위와 충돌

### 고도화 Issue의 Evidence 계약

성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI처럼 **도입 효과 또는 개선 효과를 주장하는 Issue**는 구현 전에 다음을 적는다.

1. 현재 구조와 문제/한계
2. Before에서 재현하거나 측정할 대상
3. After에서 같은 조건으로 다시 검증할 대상
4. 기능 정합성 회귀 항목
5. 저장할 Evidence 경로 또는 산출물
6. 정량 비교가 의미 없으면 `NOT_APPLICABLE`인 이유와 대신 사용할 기능/장애/정합성 증거

측정하기 전에 임의의 개선 수치를 미리 작성하지 않는다.

실제 실행 상태는 GitHub `status:*` Label 하나로 관리한다.

```text
status:human-answer-required
status:in-progress
status:final-human-review
```

- Human 결정 필요 → `status:human-answer-required`
- 구현·수정 진행 → `status:in-progress`
- Sprint Merge 전 확인 단계 → `status:final-human-review`

## 6. 구현과 Draft PR

`status:in-progress` 이후 구현 AI는 다음을 수행한다.

1. 대상 Issue 전용 브랜치 확인 또는 최신 `develop` 기준 생성
2. Issue 최신 계약과 Evidence 계획 확인
3. 고도화 Issue면 기존 구조에서 Before 문제·기준값 재현
4. 코드·테스트·필요 문서 구현
5. 변경 기능 관련 테스트 실행
6. Before와 동일 조건으로 After 재검증
7. 성능 개선 뒤에도 기능·정합성·멱등성 계약이 유지되는지 회귀 검증
8. 구조화 로그·메트릭·장애 복구 증거가 범위에 포함되면 확인
9. Evidence 문서 또는 결과 파일 갱신
10. 핵심 기능 직접 검증
11. 전체 build 실행
12. 실제 Diff 자체 검토
13. `skills/bobfull-pr-explain/SKILL.md` 적용
14. Issue 관련 변경만 Commit·Push
15. develop 대상 Draft PR 생성
16. **즉시 `skills/bobfull-pr-review/SKILL.md`를 적용해 독립 리뷰 패스 실행 및 PR 댓글 작성**

### 필수 검증 기준

기능 PR의 최소 필수 검증은 다음이다.

```text
관련 테스트 PASS
+ 전체 build PASS
+ 변경한 핵심 기능 직접 검증 PASS
+ 고도화 PR이면 Before/After Evidence PASS 또는 NOT_APPLICABLE 근거 명확
```

직접 검증 예:

- HTTP/API: Postman, curl 또는 동등한 실제 요청
- Scheduler/Consumer/Event: 해당 동작을 실제 실행 가능한 테스트·로그·직접 트리거
- 문서/설정 전용: 적용 결과 또는 정적 검증

기능과 관계없는 추가 검증을 무조건 늘리지 않는다.

## 7. Evidence-Driven V3 고도화

V3에서 `개선했다`, `유실을 막았다`, `안정성이 높아졌다`, `빨라졌다`, `무중단이다`, `확장된다` 같은 표현은 **실제 검증 범위 안에서만** 사용한다.

### Evidence 유형

| 고도화 종류 | 우선 Evidence |
|---|---|
| 성능 | p95/p99, 처리량, 오류율, 쿼리·DB Pool·CPU 등 |
| 신뢰성 | 유실·재시작 복구·재시도·최종 실패·중복 처리 |
| 동시성 | 초과 처리, 중복 생성, 락 대기, deadlock/timeout |
| 인프라 | 장애 전환, 실패 요청, Target 상태, 배포 중 요청 연속성 |
| 캐시 | 응답시간·DB 부하 + stale/무효화/최종 DB 검증 |
| Kafka/Outbox | Lag·backlog·처리량·Retry/DLT·멱등·장애가 다른 처리로 번지는지 |
| AI | 고정 입력셋 결과 + timeout/5xx 시 기본 서비스 영향 |
| 보안/제약 | 우회 재현 Before → 차단 After |
| 단순 기능 | 정상·실패·경계 계약 검증. 의미 없는 성능 측정은 하지 않음 |

### 비교 원칙

- Before/After는 가능한 한 **같은 환경·데이터·부하 조건**으로 비교한다.
- Commit SHA, 실행 조건, 사용한 Fake/Mock/Sandbox 범위를 기록한다.
- 서로 다른 환경의 숫자를 같은 Before/After처럼 비교하지 않는다.
- 개선 수치만 보고 정합성 회귀를 생략하지 않는다.
- 원본 로그 대용량을 Git에 무조건 넣지 않는다. 재현 명령·핵심 결과·작은 CSV/JSON·대표 로그·결론을 남긴다.
- 공통 Evidence 규칙과 권장 경로는 `docs/110-records/evidence/v3/README.md`를 따른다.

## 8. 담당 구현 AI의 독립 리뷰 패스

### 8.1 실행 원칙

리뷰는 별도 AI 제품이 아니라 **해당 PR을 구현한 담당 AI가 역할을 구현자에서 리뷰어로 전환해 수행**한다.

리뷰할 때 구현 중 기억을 정답으로 가정하지 않고 다음을 최신 GitHub 상태에서 다시 읽는다.

- 연결 Issue 최신 계약
- 최신 Head SHA
- base 대비 실제 Diff
- 관련 테스트·전체 build·직접 검증 결과
- 고도화 PR의 Before/After Evidence와 비교 조건
- 기존 리뷰 댓글과 미해결 지적

리뷰 기준은 `skills/bobfull-pr-review/SKILL.md`를 따른다.

### 8.2 자동 실행 시점

- Draft PR 생성 직후
- 담당 구현 AI가 기존 PR에 새 Commit을 Push한 직후
- BLOCKER/MAJOR 수정 Push 직후
- Merge 전 Head가 마지막 리뷰 Head와 달라진 경우

별도 Human 명령을 기다리지 않는다.

### 8.3 V3 Sprint Review 기준

```text
BLOCKER    → Merge 금지
MAJOR      → Merge 금지
MINOR      → 기록 후 Merge 가능
SUGGESTION → 기록 후 Merge 가능
PASS       → 현재 Merge를 막을 치명적 문제 없음
```

리뷰 흔적을 만들기 위해 중요하지 않은 문제를 억지로 찾지 않는다.

## 9. PR Human 이해도

### 기본

```text
Human 이해도 질문: 0개
```

문서·설정·단순 CRUD·기존 패턴 반복에는 질문을 만들지 않는다.

### 강화

```text
Human 이해도 질문: 정확히 3개
```

1. 핵심 실행 흐름과 주요 분기
2. 가장 중요한 기술 개념과 실제 적용 이유
3. 설계 선택 이유, 주요 실패 처리와 남은 한계

질문은 코드 암기가 아니라 실제 기능을 이해하는 수준으로 작성한다.

## 10. 리뷰 반영

### Merge 전 반드시 수정

- BLOCKER
- MAJOR
- 필수 검증 실패
- 고도화 효과를 주장하지만 필수 Evidence가 없는 경우
- 범위 안의 명확한 핵심 기능 오류

### Merge를 막지 않고 기록

- MINOR
- SUGGESTION
- 범위 밖 개선
- 추가 최적화·리팩터링
- 현재 요구 기능을 깨뜨리지 않는 테스트 보강 제안

수정 Push 뒤에는 같은 담당 구현 AI가 최신 Head를 다시 리뷰하고 새 댓글을 남긴다.

## 11. V3 Sprint Merge 전 확인

다음만 모두 만족하면 Merge 가능하다.

```text
[필수] 전체 build PASS 또는 해당 없음 근거 명확
[필수] 변경 핵심 기능 직접 검증 PASS 또는 해당 없음 근거 명확
[고도화 PR] Before/After Evidence PASS 또는 NOT_APPLICABLE 근거 명확
[필수] 최신 Head 담당 구현 AI Review 완료
[필수] 미해결 BLOCKER 없음
[필수] 미해결 MAJOR 없음
[필수] Human 결정 필요 사항 없음
[강화 PR만] Human 이해도 3문항 완료
```

필수 Human Approve 수는 `0`이다.
MINOR/SUGGESTION은 Merge를 막지 않는다.
Merge는 담당 Human이 수행한다.
