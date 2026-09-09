# BobFull V3 Sprint AI Review·Human 이해 가이드

## 1. 현재 리뷰 목적

V3 Sprint Mode의 리뷰 목적은 **완벽한 코드 만들기**가 아니라 **치명적인 문제와 근거 없는 개선 주장을 Merge 전에 잡는 것**이다.

따라서 리뷰는 다음을 우선한다.

```text
기능이 실제로 깨지는가
데이터·권한·결제·환불·예약 정합성이 깨지는가
런타임에서 주요 장애가 나는가
핵심 실패 처리가 빠졌는가
전체 build·직접 검증 결과가 사실인가
고도화 효과를 주장한다면 Before/After Evidence가 실제로 그 주장을 지지하는가
```

스타일·취향·범위 밖 개선으로 Merge를 지연시키지 않는다.

## 2. 리뷰가 가벼워져도 유지하는 안전선

Sprint Mode는 리뷰 깊이를 줄일 뿐 다음 안전선을 제거하지 않는다.

- Issue 최신 계약과 실제 Diff 일치 확인
- 범위 밖 변경 확인
- 인증·인가·소유권 확인
- 데이터·상태·금액·수량 정합성 확인
- Transaction·Rollback·Lock·멱등성·외부 I/O·Event 경계 확인
- 실행한 테스트·build·직접 검증·Evidence만 사실대로 기록
- 고도화 PR의 Before/After 비교 조건·Commit SHA·환경 기록 확인
- 비밀정보·개인정보 노출 확인
- 정책·API·DB·권한·트랜잭션 재결정은 Human에게 요청
- 이전 Head의 PASS를 최신 Head에 재사용하지 않음

즉 **확인 항목은 유지하되 Merge를 막는 등급을 BLOCKER/MAJOR로 좁힌다.**

## 3. 역할

```text
각 PR의 담당 구현 AI
= 구현·테스트·PR 설명
+ 고도화면 Before/After Evidence 기록
+ Draft PR 생성 직후 독립 리뷰 패스
+ 중요도 순 PR 댓글
+ BLOCKER/MAJOR 수정·재리뷰

담당자 Human
= 실제 정책 결정 + 강화 PR 이해도 + Merge 판단

Human Reviewer
= 선택적 추가 리뷰, V3 Sprint Mode 필수 승인 조건 아님
```

별도 GitHub Copilot Code Review는 필수 워크플로우에 포함하지 않는다.

## 4. 리뷰 시작 조건

담당 구현 AI는 다음 시점에 **별도 Human 명령 없이** `skills/bobfull-pr-review/SKILL.md`를 실행한다.

- Draft PR 생성 직후
- 해당 PR에 새 Commit을 Push한 직후
- BLOCKER/MAJOR 수정 Push 직후
- Merge 전 Head가 마지막 리뷰 Head와 달라졌을 때

`PR #번호 검토하라`는 수동 재검토가 필요할 때만 사용하는 보조 명령이다.

## 5. 독립 리뷰 패스

같은 AI가 리뷰하더라도 구현 중 자신의 판단을 그대로 재사용하지 않는다.
최신 GitHub 상태에서 다음을 다시 확인한다.

- 연결 Issue 최신 계약
- 최신 PR Head SHA
- base 대비 실제 Diff
- 관련 테스트 결과
- 전체 build 결과
- 핵심 기능 직접 검증 결과
- 고도화 PR의 Before/After Evidence와 원본 산출물 경로
- 기존 리뷰 댓글·미해결 지적

리뷰 기준 Head가 바뀌면 이전 PASS를 재사용하지 않는다.

## 6. V3 Sprint 리뷰 기준

### 최우선

- 요구한 핵심 기능이 실제로 동작하지 않음
- 데이터 손실·중복 처리·잘못된 상태 전이
- 결제·환불·예약·좌석·권한의 치명적 정합성 문제
- 주요 런타임 예외·NPE·컴파일/빌드 문제
- Transaction·Rollback·Lock·멱등성·외부 I/O 경계의 치명적 오류
- 인증·인가·소유권 우회
- 필수 실패 처리 누락
- 테스트·build·직접 검증 결과를 사실과 다르게 기록
- `성능 향상`, `유실 방지`, `무중단`, `장애가 다른 처리로 번지지 않음`, `확장성 개선` 등을 주장하지만 필요한 Evidence가 없음
- Before/After가 서로 다른 환경·데이터·부하 조건인데 직접 개선율처럼 해석함
- After 수치만 좋아졌지만 정합성 회귀가 발생함

### 필요할 때만

- 유지보수성
- 코드 명확성
- 추가 테스트
- 추가 리팩터링
- 구조 개선
- 성능 최적화

현재 요구 기능을 깨지 않는다면 Merge 차단보다 기록을 우선한다.

## 7. Evidence 리뷰 규칙

고도화 PR이면 다음 순서로 확인한다.

1. **주장 식별**: PR이 무엇이 좋아졌다고 주장하는가?
2. **Before 존재**: 기존 구조에서 문제·기준값을 실제로 재현했는가?
3. **After 동일 조건**: 환경·데이터·부하·Fake/Mock/Sandbox 조건이 비교 가능한가?
4. **정합성 유지**: 성능/격리 개선 뒤 기존 기능·상태·멱등성이 깨지지 않았는가?
5. **추적성**: Before/After SHA와 Evidence 경로가 남아 있는가?
6. **표현 범위**: 실제 검증한 범위보다 넓게 `완전 해결`, `무중단 보장`, `유실 0 보장`처럼 과장하지 않았는가?

### Merge 차단 판단

- 핵심 고도화 목적 자체가 Evidence로 검증되지 않으면 `MAJOR` 후보다.
- 조작·허위 기록 또는 실제 결과와 정면으로 모순되는 주장은 `BLOCKER`까지 가능하다.
- 단순 CRUD·문서·DTO처럼 비교가 의미 없고 `NOT_APPLICABLE` 근거가 타당하면 문제로 만들지 않는다.
- 의미 없는 성능 숫자를 억지로 요구하지 않는다.

## 8. 중요도와 Merge 영향

### BLOCKER — Merge 금지

- 보안·권한 우회
- 데이터 손실·중복 결제 등 치명적 정합성 오류
- 핵심 계약 정면 위반
- build 불가 또는 핵심 기능 완전 실패
- 검증 결과를 허위로 기록하거나 실제 결과와 정면으로 모순되는 핵심 주장

### MAJOR — Merge 금지

- 주요 요구사항 실패
- 주요 런타임 오류
- 필수 실패 처리 누락
- 실제 운영 흐름에서 높은 확률로 잘못된 결과 발생
- 이 PR의 핵심 고도화 효과를 주장하지만 필요한 Before/After Evidence가 없음
- 비교 불가능한 조건의 수치를 근거로 개선 완료를 결론냄

### MINOR — Merge 가능

- 핵심 기능은 정상이나 품질 개선 가치가 있음
- 추가 테스트·명확성·작은 유지보수성 문제
- 핵심 결론을 바꾸지 않는 Evidence 표현·정리 개선

### SUGGESTION — Merge 가능

- 선택적 개선
- 향후 리팩터링·최적화 아이디어

### PASS

보고할 BLOCKER·MAJOR가 없고 필수 검증 및 필요한 Evidence가 사실과 일치함.

> V3 Sprint Mode에서 `PASS`는 “완벽함”이 아니라 “현재 Merge를 막을 치명적 문제가 보이지 않음”을 뜻한다.

## 9. 리뷰 댓글

각 정식 리뷰 패스는 PR Conversation 댓글을 남긴다.

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

- BLOCKER/MAJOR 하나라도 있으면 `BLOCK`
- MINOR/SUGGESTION만 있으면 `MERGEABLE`
- 지적을 만들기 위해 억지로 문제를 생성하지 않는다.

## 10. 필수 검증

### 기능 PR

```text
관련 테스트 PASS
전체 build PASS
변경 핵심 기능 직접 검증 PASS
고도화 PR이면 Before/After Evidence PASS 또는 NOT_APPLICABLE 근거 명확
```

HTTP/API 변경은 Postman, curl 또는 동등한 실제 요청을 우선한다.

### 문서·설정 PR

- 전체 build가 의미 없으면 `NOT_RUN` 이유 명시 가능
- 정적 검사·렌더링·설정 적용 등 변경과 직접 관련된 검증 수행
- Before/After가 의미 없으면 `NOT_APPLICABLE` 이유를 명시

실행하지 않은 것을 PASS로 기록하지 않는다.

## 11. 리뷰 반영

### Merge 전에 수정

- BLOCKER
- MAJOR
- build 실패
- 직접 검증 실패
- 필요한 Evidence 누락 또는 비교 조건 오류
- 핵심 기능 오류

### Merge 후 처리 가능

- MINOR
- SUGGESTION
- 범위 밖 리팩터링
- 추가 최적화
- 현재 기능을 깨지 않는 추가 테스트

수정 Push 뒤 같은 담당 구현 AI가 최신 Head를 즉시 재리뷰하고 새 댓글을 남긴다.

## 12. Human 이해도

### Issue 단계

V3 Sprint Mode에서는 학습용 질문을 구현 착수 조건으로 사용하지 않는다.
Human 질문은 실제 결정이 필요할 때만 한다.

### PR 기본

```text
Human 이해도 질문: 0개
```

### PR 강화

```text
Human 이해도 질문: 정확히 3개
```

1. 핵심 실행 흐름과 주요 분기
2. 가장 중요한 기술 개념과 실제 적용 위치·이유
3. 설계 선택 이유, 주요 실패 처리와 남은 한계

목표는 클래스·메서드 암기가 아니라 **담당자가 자신이 만든 기능을 설명할 수 있는 상태**다.

## 13. V3 Sprint Merge 전 확인

Merge 차단 조건은 다음으로 제한한다.

```text
전체 build FAIL 또는 필요한데 미실행
핵심 기능 직접 검증 FAIL 또는 필요한데 미실행
고도화 PR의 Before/After Evidence FAIL 또는 필요한데 미작성
최신 Head 담당 구현 AI Review 미완료
미해결 BLOCKER
미해결 MAJOR
Human 결정 필요 사항 미해결
강화 PR Human 3문항 미완료
```

그 외 MINOR·SUGGESTION은 기록 후 Merge 가능하다.
필수 Human Approve 수는 `0`이다.
Merge는 담당 Human이 수행한다.

## 14. 금지 사항

- 중요하지 않은 문제로 Merge 지연
- MINOR/SUGGESTION을 BLOCKER처럼 취급
- 실행하지 않은 검증·Evidence를 PASS 처리
- 다른 환경의 숫자를 같은 Before/After처럼 비교
- Evidence 없는 개선 주장을 사실로 승인
- 이전 Head 리뷰를 최신 Head 리뷰로 재사용
- Human 답변 대리 작성
- 정책·API·DB·권한·트랜잭션 임의 결정
- AI가 Merge 수행
