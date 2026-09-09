# Issue #256 이메일 Outbox 요청 스레드 격리 Evidence

## 검증 대상

이메일 Outbox가 커밋 뒤 즉시 SMTP 처리를 시작하더라도, 요청 스레드는 전용 bounded executor에 작업을
제출한 뒤 반환해야 한다. 공통 `AfterCommitExecutor`와 ChatRoom AFTER_COMMIT 경로는 변경하지 않는다.

## 측정 계약

- Primary KPI: 느린 SMTP를 모사한 `processor.signal()`이 있어도 `dispatch()`가 500ms 미만에 반환한다.
- Secondary KPI: executor 제출 거부 시 `processor.signal()`이 호출되지 않는다.
- 안전 확인: 이메일 발송 실패에도 결제·예약·참여자 트랜잭션은 커밋되고 이메일 Outbox는 `PENDING`으로 재시도된다.

## 기준 코드

- Before SHA: `e108c72faef56f4ee7708951f1f885a234f19044`
- 최신 develop 병합 검증 SHA: `759b87da1e095e159c581e2beac2ef250bddfa15`

## 환경·데이터·실행 조건

- 로컬 macOS, Java 17, Gradle 9.5.1
- `EmailOutboxSignalDispatcherTest`는 Mockito로 `processor.signal()`을 latch로 대기시켜 느린 SMTP I/O를 모사했다.
- `PaymentReservationConfirmationTransactionIntegrationTest`는 H2(MySQL mode)와 Fake 알림 adapter를 사용했다.

## Before 결과

코드 정적 확인 결과 `EmailOutboxEventService.enqueue()`의 AFTER_COMMIT 콜백이
`EmailOutboxProcessor.signal()`을 직접 호출했다. 따라서 SMTP 처리 시간이 요청 스레드 반환 시간에 포함됐다.
실환경 K6 재측정이나 실제 SMTP 지연 주입은 이번 작업에서 수행하지 않았다.

## 변경 내용

- 이메일 전용 bounded `ThreadPoolTaskExecutor`를 추가했다.
- AFTER_COMMIT에서는 `EmailOutboxSignalDispatcher.dispatch()`만 호출하고, 실제 Processor/SMTP는 executor 스레드에서 실행한다.
- 제출 거부 시 claim·complete를 호출하지 않고 경고 로그만 남겨 `PENDING` 이벤트를 기존 Scheduler가 회수할 수 있게 했다.
- production/local SMTP connection/read/write timeout을 5초 기본값으로 설정했다.

## After 결과

| 지표·현상 | Before | After | 판정 |
|---|---|---|---|
| 느린 signal 중 dispatch 반환 | 요청 스레드에서 직접 실행 | 500ms 미만 반환 단위 테스트 통과 | PASS |
| executor 제출 거부 | 전용 경계 없음 | processor 미호출 단위 테스트 통과 | PASS |
| 이메일 실패 시 핵심 트랜잭션 | #183 계약 유지 필요 | 결제 PAID·예약/참여자 저장, Email Outbox PENDING 확인 | PASS |
| 공통 AfterCommitExecutor | 동기 실행 | 기존 동기 회귀 테스트 통과 | PASS |

## 정합성 회귀 검증

- `AfterCommitExecutorTest`: 트랜잭션 동기화가 있으면 afterCommit에서 동기 실행되는 기존 계약을 확인했다.
- `PaymentReservationConfirmationTransactionIntegrationTest`: 이메일 실패가 핵심 결제·예약 트랜잭션을 롤백하지 않으며 PENDING 재시도 상태를 남기는 것을 확인했다.
- `ChatRoomOutboxProcessorIntegrationTest`, `ReservationConfirmationServiceTest`: ChatRoom Outbox의 기존 AFTER_COMMIT 경로 회귀를 확인했다.
- `RecruitmentDeadlineNotificationIntegrationTest`: 이메일 AFTER_COMMIT 처리의 비동기 완료를 즉시 단정하지 않고 2초 이내 eventual 처리로 검증한다.

## 최신 검증 결과 (latest develop merge 이후)

- 관련 회귀 8개 클래스: `BUILD SUCCESSFUL in 12s`
  - `EmailOutboxSignalDispatcherTest`, `EmailOutboxEventServiceTest`, `EmailOutboxProcessorTest`
  - `PaymentReservationConfirmationTransactionIntegrationTest`, `AfterCommitExecutorTest`
  - `ChatRoomOutboxProcessorIntegrationTest`, `ReservationConfirmationServiceTest`
- `RecruitmentDeadlineNotificationIntegrationTest`: `BUILD SUCCESSFUL in 10s`
- 일반 CI 조건(OpenAI API key·provider·heldout opt-in 환경변수 제거):
  `./gradlew clean build --console=plain` → `BUILD SUCCESSFUL in 2m 22s`
- 이전 `FakeAiModerationAdapterTest`의 `compileTestJava` blocker와 전체 build BLOCK은 #261 / PR #262가 develop에 병합되며 해결됐다. 이 PR에서는 해당 코드를 변경하지 않았다.

## 구조화 로그·메트릭

제출 거부 시 `event=EMAIL_OUTBOX_SIGNAL_REJECTED`, `outboxEventId`, `status=PENDING`을 남긴다. 새 메트릭은 추가하지 않았다.

## Executor 초기 sizing 근거

#146 AWS 실측에서 실제 SMTP 발송 시간은 평균 약 0.5~1초, p99 약 1.5초로 관측됐다.
이 범위를 초기값 판단 근거로 삼아 `core-pool-size=2`, `max-pool-size=2`, `queue-capacity=100`을 유지한다.

- 평균 SMTP 1초를 기준으로 worker 2개는 약 2건/초의 병렬 처리 용량이다.
- p99 SMTP 1.5초를 기준으로도 약 1.3건/초의 병렬 처리 용량이다.
- 이메일은 핵심 요청 응답과 분리돼 있고, 순간 적체는 queue 100이 흡수한다.
- executor가 포화돼도 제출 거부 시 Outbox는 `PENDING`으로 남고 기존 Scheduler가 재처리한다.

따라서 worker를 과도하게 늘리기보다 외부 SMTP 지연·장애 시 동시 연결과 자원 사용을 제한하는
보수적 초기 bounded 설정으로 판단했다. 이 값은 실제 이메일 도착률을 기준으로 최적화한 값이 아니며,
운영에서 queue depth와 rejection 로그를 관측한 뒤 환경변수로 조정할 수 있는 초기값이다.

## 결과 해석

이번 검증은 요청 스레드와 SMTP I/O의 코드 경계 및 실패 격리를 확인한다. Outbox의 내구성·stale 복구·수신자별 멱등성은 #183 공통 구현과 기존 Scheduler 정책을 재사용한다.

## 검증 한계

- 실제 SMTP 서버, 실제 HTTP 요청, K6/AWS 환경에서 timeout 및 health 회복을 재측정하지 않았다.
- latch/Fake 기반 테스트는 executor 제출 경계와 상태 분리를 검증하지만 SMTP 프로토콜 자체의 timeout 동작은 검증하지 않는다.

## 관련

- Issue: #256
- PR: Draft PR 생성 후 갱신 예정
- ADR: 해당 없음
- 기존 Evidence: `docs/110-records/evidence/v3/183-email-outbox/README.md`
