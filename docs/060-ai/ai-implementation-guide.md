# BobFull 담당자 AI 실행 가이드

## 1. V3 Sprint Mode

현재 V3 마무리 기간에는 **병목을 줄이고 핵심 위험만 차단**한다.

구현 담당자 AI의 목표는 완벽한 코드를 오래 다듬는 것이 아니라 다음을 빠르게 만족시키는 것이다.

```text
요구 기능 정상 동작
+ 관련 테스트 PASS
+ 전체 build PASS
+ 직접 검증 PASS
+ 고도화 PR이면 Before/After Evidence PASS 또는 NOT_APPLICABLE 근거 명확
+ 담당 구현 AI Review BLOCKER/MAJOR 없음
```

MINOR·SUGGESTION·추가 리팩터링·있으면 좋은 테스트는 Merge를 막지 않는다.

## 2. 기존 안전 규칙은 유지

Sprint Mode에서도 다음은 생략하지 않는다.

- 새 작업 시작 전 `AGENTS.md`와 필요한 온보딩 문서를 확인한다.
- `main`, `master`, `develop`에서 직접 수정하지 않는다.
- 다른 Issue 작업 브랜치에서 새 Issue 작업을 시작하지 않는다.
- 새 Issue 브랜치는 최신 `develop` 기준으로 생성한다.
- 다른 Issue의 미커밋 변경을 임의 이동하지 않는다.
- 한 번에 하나의 Issue만 처리한다.
- Issue 최신 계약 범위 밖 기능·리팩터링·새 기술을 임의 추가하지 않는다.
- 인증 사용자 ID·권한·금액·상태를 클라이언트 입력만으로 신뢰하지 않는다.
- 실행하지 않은 테스트·build·직접 검증·Evidence를 PASS로 기록하지 않는다.
- 비밀정보·개인정보를 Commit하지 않는다.
- 정책·API·DB·권한·트랜잭션 재결정은 Human에게 요청한다.

세부 기준은 다음 원본을 따른다.

- `AGENTS.md`
- `docs/050-engineering/github-rules.md`
- `docs/050-engineering/test-convention.md`
- `skills/bobfull-onboarding/SKILL.md`

## 3. 역할

각 Issue의 구현 담당 AI는 다음을 끝까지 담당한다.

```text
Issue 분석
→ 고도화면 Before 재현·기준값 확보
→ 구현
→ 테스트·After 동일 조건 재검증
→ Evidence 기록
→ 직접 검증·build
→ PR Explain
→ Draft PR 생성
→ 독립 리뷰 패스
→ PR 댓글
→ 치명적 지적 수정·재검증·재리뷰
```

여기서 `독립 리뷰 패스`는 별도의 GitHub Copilot이나 다른 AI 제품을 의미하지 않는다.
**같은 담당 구현 AI가 구현자 역할에서 리뷰어 역할로 전환하고 최신 GitHub 근거를 처음부터 다시 읽는 단계**다.

## 4. Issue 실행 — 결정이 없으면 바로 진행

Issue 단계에서 학습용 질문을 구현 착수 조건으로 만들지 않는다.

다음이 확정돼 있으면 바로 구현한다.

- 요구 기능
- 변경 범위와 제외 범위
- 기존 코드에서 확인 가능한 상태/API/DB 계약

Human 질문은 실제로 선택이 필요한 경우에만 한다.

- 정책 재결정
- API·DB·상태·권한 변경
- 금액·환불·트랜잭션·보상 정책 결정
- 다른 담당자 범위 충돌

불필요한 질문으로 구현을 대기시키지 않는다.

### 고도화 Issue 착수 전 확인

성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI 등 개선 효과를 주장하는 Issue라면 구현 전에 다음이 있어야 한다.

1. 현재 구조/문제 또는 기준값
2. Before 재현·측정 방법
3. After 동일 조건 재검증 방법
4. 정합성 회귀 검증
5. Evidence 저장 경로 또는 산출물

정량 측정이 의미 없는 단순 기능은 억지 숫자를 만들지 않고 `NOT_APPLICABLE` 이유와 정상·실패·경계 증거로 대체한다.

실제 상태는 GitHub `status:*` Label 하나로 관리한다.

```text
status:human-answer-required
status:in-progress
status:final-human-review
```

## 5. 구현·Draft PR

`status:in-progress` 이후 다음 순서로 진행한다.

1. 대상 Issue 전용 브랜치 확인 또는 최신 `develop` 기준 생성
2. Issue 최신 계약·제외 범위·Evidence 계획 확인
3. 고도화 Issue면 기존 구조에서 Before 문제 또는 기준값 재현
4. 코드·테스트·필요 문서 구현
5. 변경 기능 관련 테스트 실행
6. Before와 같은 환경·데이터·부하 조건으로 After 재검증
7. 개선 뒤에도 기능·상태·멱등성·정합성이 유지되는지 회귀 검증
8. 범위에 포함된 구조화 로그·메트릭·장애 복구 동작 확인
9. `docs/110-records/evidence/v3/...` 또는 Issue가 정한 Evidence 산출물 갱신
10. 핵심 기능 직접 검증
11. 전체 build 실행
12. 전체 Diff 자체 검토
13. `skills/bobfull-pr-explain/SKILL.md` 적용
14. Issue 관련 변경만 Commit·Push
15. develop 대상 Draft PR 생성
16. **별도 Human 명령 없이 즉시 `skills/bobfull-pr-review/SKILL.md` 실행**
17. 최신 Head 기준 Review 댓글 작성

### 직접 검증 기준

- HTTP/API: Postman, curl 또는 동등한 실제 요청으로 원하는 성공 흐름 확인
- 결제·예약·환불: 핵심 상태 변화와 외부/내부 결과 확인
- Scheduler/Event/Consumer: 해당 동작을 실제 실행 가능한 테스트·트리거·로그로 확인
- 문서/설정: 정적 검사 또는 실제 적용 결과 확인

핵심 성공 흐름이 정상이고 전체 build가 통과하면, 범위 밖 시나리오까지 무제한으로 늘리지 않는다.

## 6. Before/After Evidence 실행 규칙

### 6.1 무엇을 Evidence로 볼 것인가

| 유형 | 예시 |
|---|---|
| 성능 | p95/p99, RPS, 오류율, 쿼리 수, DB Pool, CPU |
| 신뢰성 | 유실 건수, 재시작 복구, 재시도, 최종 실패, 중복 처리 |
| 동시성 | 초과 예약, 중복 생성, 락 대기, timeout/deadlock |
| 인프라 | 장애 시 실패 요청, 전환 시간, Target/Health 상태 |
| 캐시 | 응답시간·DB 부하 + stale/무효화/DB 최종 검증 |
| Kafka/Outbox | Consumer Lag, backlog, 처리량, Retry/DLT, 멱등성 |
| AI | 고정 입력셋 결과 + AI 장애 시 기본 서비스 영향 |
| 보안/제약 | Before 우회 재현 → After 차단 |
| 단순 기능 | 정상·실패·경계 계약. 정량 비교는 N/A 가능 |

### 6.2 동일 조건 원칙

- Before/After는 가능한 한 같은 Commit 기준 외 조건을 맞춘다.
- 애플리케이션 인스턴스 수, DB 종류·버전, 테스트 데이터, Pool, 부하 조건, Fake/Mock/Sandbox 여부를 기록한다.
- 다른 환경의 숫자를 직접 개선율로 비교하지 않는다.
- Before 문제를 실제로 재현하지 못했다면 `재현 성공`이라고 쓰지 않는다.
- After 개선을 확인해도 정합성 회귀가 있으면 성공으로 판정하지 않는다.

### 6.3 Evidence 저장

권장 경로:

```text
docs/110-records/evidence/v3/<issue-number>-<short-name>/README.md
```

최소 기록:

```text
검증 대상
Before SHA / After SHA
환경·데이터·실행 조건
Before 결과
변경 내용
After 결과
정합성 검증
구조화 로그·메트릭
결과 해석
검증 한계
Issue / PR / ADR / Troubleshooting 링크
```

대용량 원본 로그를 무조건 Git에 넣지 않는다. 재현 명령·핵심 결과·대표 로그·작은 CSV/JSON 등 다시 설명 가능한 최소 증거를 저장한다.

## 7. PR Explain

PR 설명은 팀원이 빠르게 이해할 수 있게 작성한다.

```text
한 줄 요약 / 관련 Issue
→ PR 이해 요약
→ 상세 변경 및 검증
→ Before/After Evidence 요약
→ Human 이해 확인
→ V3 Sprint Merge 전 확인
```

필수:

- 무엇을 왜 바꿨는지
- 핵심 실행 흐름
- 중요한 기술 개념
- 주요 트레이드오프와 남은 한계
- 전체 build / 직접 검증 결과
- 고도화 PR이면 Before/After 핵심 결과와 Evidence 경로
- 남아 있는 실제 위험

단순 변경에 의미 없는 Mermaid·개념·트러블슈팅·정량 실험을 억지로 만들지 않는다.

## 8. 담당 구현 AI Review

### 8.1 자동 실행

다음 시점에는 추가 명령 없이 리뷰한다.

- Draft PR 생성 직후
- 해당 AI가 새 Commit을 Push한 직후
- BLOCKER/MAJOR 수정 Push 직후
- Merge 전 최신 Head가 마지막 리뷰 Head와 다를 때

`PR #번호 검토하라`는 예외적인 수동 재검토 요청일 뿐, 최초 리뷰 시작 명령이 아니다.

### 8.2 독립성 확보

리뷰할 때 다음을 다시 조회한다.

1. 연결 Issue 최신 계약
2. 최신 Head SHA
3. 실제 Diff
4. 테스트·전체 build·직접 검증 결과
5. 고도화 PR의 Before/After Evidence와 동일 조건 여부
6. 기존 리뷰·댓글

구현 중 기억이나 기존 PR 설명만 보고 PASS하지 않는다.

### 8.3 Merge 영향

- `BLOCKER`: 반드시 수정
- `MAJOR`: 반드시 수정
- `MINOR`: 기록 후 Merge 가능
- `SUGGESTION`: 기록 후 Merge 가능

리뷰 결과는 PR Conversation 댓글에 중요도 순으로 남긴다.

## 9. 리뷰 반영

### 즉시 수정

- 기능 실패
- 전체 build 실패 원인
- 직접 검증 실패
- 필요한 Before/After Evidence 누락 또는 비교 조건 불일치
- BLOCKER
- MAJOR
- 데이터 정합성·권한·결제·환불·예약 핵심 오류

### 후속으로 남겨도 됨

- MINOR
- SUGGESTION
- 범위 밖 리팩터링
- 추가 최적화
- 현재 요구 기능을 깨지 않는 추가 테스트 제안

수정 후 필요한 테스트·직접 검증·Evidence·전체 build를 다시 수행하고 Push한다.
**Push 직후 같은 담당 AI가 최신 Head를 다시 리뷰하고 새 댓글을 남긴다.**

## 10. PR Human 이해도

### 기본

```text
질문 0개
```

### 강화

```text
정확히 3개
```

1. 핵심 실행 흐름과 주요 분기
2. 가장 중요한 기술 개념과 적용 위치·이유
3. 설계 선택 이유, 주요 실패 처리와 남은 한계

코드 줄 암기보다 실제 기능을 설명할 수 있는지를 확인한다.

## 11. 승인과 Merge

V3 Sprint Mode의 필수 Human Approve 수는 `0`이다.

별도 리뷰어의 Approve를 기다리지 않는다.
Human 리뷰는 도움이 되면 수행하지만 필수 조건이 아니다.

담당 Human은 다음을 확인하고 직접 Merge한다.

```text
전체 build PASS 또는 해당 없음 근거 명확
핵심 기능 직접 검증 PASS 또는 해당 없음 근거 명확
고도화 PR이면 Before/After Evidence PASS 또는 NOT_APPLICABLE 근거 명확
최신 Head 담당 구현 AI Review 완료
미해결 BLOCKER 없음
미해결 MAJOR 없음
Human 결정 필요 사항 없음
강화 PR이면 Human 3문항 완료
```

위 조건을 만족하면 MINOR·SUGGESTION이 남아 있어도 진행할 수 있다.
