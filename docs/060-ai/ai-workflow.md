# BobFull AI Issue 워크플로우

이 문서는 Issue 단계와 Refactor Learning Mode의 기준이다. 공통 안전·상태·Human 책임은 `AGENTS.md`, 실제 구현은 `ai-implementation-guide.md`, PR 설명과 Review는 각 PR Skill을 따른다.

## 1. Issue 진입

### 새 Issue 최초 처리

```text
AGENTS.md + 실제 Issue
→ bobfull-onboarding으로 필요한 문서 선택
→ 관련 코드·테스트와 직접 충돌 확인
→ 일반 / Refactor / Enhancement 분류
→ 현재 상태에 맞는 단계 수행
```

### 같은 Issue 재처리

onboarding을 형식적으로 다시 읽지 않는다. 다음만 갱신한다.

- Issue 본문과 댓글의 최신 계약
- 현재 `status:*` Label
- 연결 PR 유무
- 현재 브랜치와 작업 트리
- 이전 확인 뒤 계약 변경 여부

계약 변경으로 필요한 기준 문서가 달라졌을 때만 onboarding으로 문서를 다시 선택한다. 연결 PR이 있으면 Issue 명령으로 PR을 수정하지 않고 `PR #번호 검토하라`가 필요하다고 보고한다.

## 2. 일반 Issue

일반 Feature·문서·설정·단순 기계 작업은 학습용 질문을 구현 착수 조건으로 사용하지 않는다. Issue·코드·선택한 기준 문서로 계약이 충분하면 `AGENTS.md`의 상태 규칙에 따라 구현 단계로 이동한다.

Human 질문은 다음 결정이 실제로 남았을 때만 한다.

- 정책 선택
- API·DB·상태 계약 변경
- 권한·보안·금액·트랜잭션·보상 정책 결정
- 다른 담당자 범위와 충돌

질문이 필요하면 Issue에 필요한 내용만 기록하고 구현을 중단한다. Human 답변은 AI가 대신 작성하지 않는다.

## 3. Refactor Learning Mode

이 절은 Refactor Learning Mode의 단일 기준이다. 다른 문서와 템플릿은 이 절을 참조하고 적용 대상·질문 축·상태 흐름을 다시 정의하지 않는다.

### 적용 대상

`.github/ISSUE_TEMPLATE/refactor.md`를 사용하는 의미 있는 구조 변경에 적용한다.

- 패키지·모듈 구조 변경
- 클래스·객체 책임 분리
- 도메인 간 의존성 변경
- Port / Adapter 경계 변경
- `common`, `kafka`, `outbox` 등 코드 소유 위치 변경

다음처럼 결정된 설계를 적용하는 기계 작업에는 적용하지 않는다.

- import 또는 package declaration 일괄 수정
- 파일 rename·단순 이동
- 주석·경로·formatting 수정
- 이미 승인된 리팩토링에 딸린 반복 작업
- Refactor Learning Mode 자체를 추가·수정하는 문서·템플릿 작업

마지막 항목은 Issue 계약이 Refactor Learning Mode 적용을 별도로 요구하면 예외다.

### 최초 처리

AI는 파일을 수정하기 전에 현재 코드·문서·테스트를 분석해 다음을 설명한다.

- 현재 구조와 실제 문제
- 목표 구조 또는 변경 후보
- 선택 이유와 트레이드오프
- 변경 범위와 제외 범위
- 유지해야 할 기존 동작

그 뒤 실제 Issue에 맞춘 Human 이해 질문 3개를 남기고 `status:human-answer-required`로 중단한다.

1. 현재 구조에서 무엇이 문제인가?
2. 왜 이번 목표 구조 또는 변경 방향을 선택하는가?
3. 이번 Issue에서 무엇을 변경하고 무엇을 의도적으로 유지하는가?

질문은 일반 이론이나 코드 암기가 아니라 현재 Issue의 구조와 계약을 설명할 수 있는지 확인한다.

### Human 답변 후 재개

같은 `Issue #번호 구현하라` 명령에서 답변을 실제 코드·문서와 대조한다.

- 충분함: 최종 계약을 Issue 댓글에 기록하고 `status:in-progress`로 전환해 구현한다.
- 중요한 오해나 결정 누락: AI 보완 설명과 필요한 재질문만 남기고 `status:human-answer-required`를 유지한다.
- 정책·API·DB·권한·보안·트랜잭션 재결정 필요: Human 판단 전 구현하지 않는다.

이 모드는 별도 approval 단계나 Reviewer 역할을 만들지 않는다. PR에서 Issue 단계와 같은 질문을 반복하지 않으며, PR Human 질문 체계 자체는 `ai-review-guide.md`의 현재 규칙을 유지한다.

## 4. Enhancement와 Evidence

성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI 등 개선 효과를 주장하면 구현 전에 `docs/110-records/evidence/v3/README.md`를 읽고 측정 계약을 정한다.

```text
현재 문제 또는 기준값
→ Before 재현·측정 방법
→ 동일 조건 After 검증 방법
→ 정합성 회귀 항목
→ Evidence 산출물과 한계
```

정량 비교가 의미 없으면 `NOT_APPLICABLE` 이유와 정상·실패·경계 증거를 사용한다. Evidence 세부 형식과 저장 경로를 이 문서에 복제하지 않는다.

## 5. 구현 전환

구현 가능 상태가 되면 `docs/060-ai/ai-implementation-guide.md`를 읽고 현재 Issue에 필요한 코드·테스트·계약 문서만 추가한다.

```text
최종 계약 확인
→ status:in-progress
→ 구현·검증
→ PR Explain
→ Draft PR
→ 최신 Head 담당 구현 AI Review
→ Human 최종 Merge 대기
```

세부 구현, Evidence, PR 본문, Review 등급과 Merge Gate는 각 기준 문서로 이동하고 이 문서에서 반복하지 않는다.

## 6. Issue 초안과 생성

- `새 Issue 초안 작성하라`: 작업 성격에 맞는 Issue template으로 초안만 제시한다.
- `이 초안으로 Issue 생성하라`: Human이 명시적으로 요청한 경우에만 생성한다.
- 일반 기능·문서·설정·단순 리팩토링은 `feature.md`, 의미 있는 리팩토링은 `refactor.md`를 사용한다.
- 제목 형식은 `docs/050-engineering/issue-title-rules.md`를 따른다.
