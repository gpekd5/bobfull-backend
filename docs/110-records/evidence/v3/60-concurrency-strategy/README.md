# Issue #60 — 프로젝트 동시성 제어 전략 비교 Evidence

## 기준

- 기준 Branch: `feature/60-concurrency-strategy`
- 기준 Commit SHA: `a467fd9295b0e3f29441db829856b19cc0a36161` (최신 `develop`)
- 검증일: 2026-08-12

## 요약

원래 계획은 예약/환불/AI Moderation/Outbox 4개 도메인에서 "보호장치 없는 조건 → 실패 재현 → 전략 비교"를 진행하는 것이었다.

착수 전 최신 코드를 다시 확인한 결과, **4개 도메인 모두 이미 적절한 동시성 제어 전략이 구현돼 있고, 그 전략이 실제로 대상 경쟁 시나리오를 통과시키는 기존 테스트도 존재**했다. 코드 읽기만으로는 "검증됐다"고 기록하지 않고, 아래 4개 테스트를 실제로 실행해 통과 결과를 근거로 남긴다.

이 Issue의 성과는 "새 전략을 도입했다"가 아니라 **"기존 전략이 실측으로 이미 충분함을 확인했다"**이며, 이는 완료 조건의 "미도입도 정상 결론이다"에 해당한다.

## 1. 프로젝트 동시성 경계 Inventory (실제 코드 재확인)

| 도메인/흐름 | 경쟁 대상 | 실제 방어 (파일:라인) | 실측 근거 |
|---|---|---|---|
| 예약 CREATE/JOIN | TimeSlot / Reservation | `TimeSlotRepository.findWithLockByIdAndDeletedAtIsNull` (`PESSIMISTIC_WRITE`, `TimeSlotRepository.java:18-19`), `ReservationRepository.findWithLockById` (`ReservationRepository.java:23-24`), 락 후 재검증(`ReservationPreparationService.java:92-118`), READY Payment 포함·만료 실시간 제외(`PaymentService.java:66-68`) | 실제 MySQL 3/3 PASS (아래 2절) |
| 환불 완료 | Refund / Payment / ReservationParticipant / Reservation | Refund/Payment 엔티티 가드(`Refund.java:107-129`, `Payment.markRefunded` `Payment.java:213-218`)로 Terminal 상태 역행 차단, Participant 조건부 UPDATE(`completeCancelIfRequested`, `ReservationParticipantRepository.java:55-59`), 비관적 락 병행 | H2(MySQL 모드) 22/22 PASS (아래 2절) |
| AI Moderation | ChatModeration(messageId) | `@Version` 낙관적 락(`ChatModeration.java:31-32`) + `isCompleted()` 가드(`ChatModerationService.java` `analyze` L33-37, `persistCompleted`/`persistFailure` L58-96)로 SAFE/FLAGGED 확정 후 늦은 ANALYSIS_FAILED 역행 차단 | H2 15/15 PASS (Service 14 + Repository 1, 아래 2절) |
| Outbox 처리 | OutboxEvent / EmailOutboxDelivery | `processingToken` 기반 원자적 조건부 UPDATE claim/complete/fail(`OutboxEventTransactionService.java:29-47`), 외부 I/O는 트랜잭션 밖에서 실행, stale 복구(`recoverStale`, `OutboxEventRepository.java:48-52`) | H2 9/9 PASS (ChatRoomOutboxProcessor, 아래 2절) |

문서(Issue 본문)가 가정한 "보호장치가 없거나 부족한 조건"은 실제 코드와 **일치하지 않았다** — 이 차이를 그대로 기록한다.

## 2. 실행한 검증과 결과

### 2.1 예약 좌석 경쟁 — 실제 MySQL

- 대상: `ReservationPreparationConcurrencyIntegrationTest`
- 환경: 이번 검증 전용 임시 컨테이너 `docker run mysql:8.4`(포트 33062, DB `bobfull_concurrency_test`) — 기존 개발 DB(`bobfull-mysql`, 포트 3308)와 완전히 분리, 검증 후 컨테이너 즉시 제거
- 실행 명령:
  ```bash
  BOBFULL_MYSQL_CONCURRENCY_TEST=true \
  BOBFULL_TEST_MYSQL_URL="jdbc:mysql://localhost:33062/bobfull_concurrency_test?useSSL=false&allowPublicKeyRetrieval=true" \
  BOBFULL_TEST_MYSQL_USERNAME=root \
  BOBFULL_TEST_MYSQL_PASSWORD=concurrencypass \
  ./gradlew :test --tests "com.bobfull.reservation.service.ReservationPreparationConcurrencyIntegrationTest" --info
  ```
- 결과: `tests="3" failures="0" errors="0"` (`BUILD SUCCESSFUL`)
  - `같은_회차에_동시_CREATE_준비_요청이_들어오면_유효한_CREATE_READY_Payment가_한_건만_생성되고_Reservation은_아직_생성되지_않는다` — PASS
  - `마지막_좌석에_동시_JOIN_요청이_들어오면_하나만_성공하고_실제_참여_인원과_선점_인원의_합이_정원을_넘지_않는다` — PASS
  - `같은_회원의_중복_JOIN_요청_중_하나만_성공한다` — PASS

### 2.2 환불 완료 복수 경로 경쟁 — H2(MySQL 모드)

- 대상: `RefundTransactionIntegrationTest`
- 실행 명령: `./gradlew :test --tests "com.bobfull.payment.service.RefundTransactionIntegrationTest"`
- 결과: `tests="22" failures="0" errors="0"`
- 확인된 시나리오(로그 근거): `완료_이후_같은_cancellationId의_뒤늦은_CancelPending은_순차적으로도_완료상태를_유지한다` 실행 중 `event=REFUND_STATE_TRANSITION_BLOCKED refundId=25 attempted=PROCESSING currentStatus=COMPLETED` 로그로 Terminal 상태 역행이 실제로 차단됨을 확인

### 2.3 AI Moderation 동일 messageId 경쟁 — H2

- 대상: `ChatModerationServiceTest`(14), `ChatModerationRepositoryTest`(1)
- 결과: 둘 다 전건 PASS, 낙관적 락 충돌 후 "이미 완료 상태면 재시도하지 않는다" 시나리오 포함

### 2.4 Outbox 중복 처리 — H2

- 대상: `ChatRoomOutboxProcessorIntegrationTest`
- 결과: `tests="9" failures="0" errors="0"` (동시 claim 단일 선점, stale 복구 시나리오 포함)

## 2.5 실패 재현 — 보호장치 임시 제거 후 같은 테스트 재실행

2절까지의 결과는 "현재 구현이 통과한다"만 보여준다. 통과가 실제로 보호장치 덕분인지(우연히 순차 실행돼서 통과한 게 아닌지) 확인하기 위해, 각 도메인의 핵심 보호장치를 로컬에서 잠깐 제거하고 **같은 기존 테스트**를 재실행해 실패를 재현했다. 각 실험은 재현 즉시 코드를 원복했고(`git diff` 로 원복 확인), `develop`/`feature/60-concurrency-strategy`에는 무보호 상태가 커밋되지 않았다.

| 도메인 | 제거한 보호장치 | 재실행한 테스트 | 결과 |
|---|---|---|---|
| AI Moderation | `ChatModeration.version`의 `@Version` 제거 | `ChatModerationRepositoryTest` | `AssertionError: Expecting code to raise a throwable` — 낙관락 예외가 안 던져져 늦은 실패가 조용히 SAFE/FLAGGED를 덮어씀(Lost Update 재현) |
| 환불 완료 | `RefundRepository`의 락 3종(`findWithLockById/ByCancellationId/ByPayment_Id`) 제거만으로는 **재현 안 됨**(Participant 조건부 UPDATE가 2차 방어로 여전히 막음) → `ReservationParticipantRepository.completeCancelIfRequested`의 `WHERE participationStatus = CANCEL_REQUESTED` 조건까지 함께 제거 | `RefundTransactionIntegrationTest.웹훅과_즉시응답_동시완료도_Participant를_한번만_완료한다` | `ExecutionException: CustomException: 대상 회차 또는 예약을 찾을 수 없습니다` — 두 경로 모두 완료 처리권을 얻었다고 착각해 이미 종료된 예약을 재처리하려다 예외 발생 |
| Outbox | `OutboxEventRepository.claimByTypes`의 `and e.status = :pending` 조건 제거 | `ChatRoomOutboxProcessorIntegrationTest.동시에_Claim하면_같은_이벤트는_한_Processor만_선점한다` | `AssertionError: Expecting actual: [true] not to be equal to: [true]`류 — 두 스레드 모두 선점 성공(중복 claim 재현) |
| 예약 좌석 | `TimeSlotRepository.findWithLockByIdAndDeletedAtIsNull` + `ReservationRepository.findWithLockById`의 `@Lock(PESSIMISTIC_WRITE)` 제거, 실제 MySQL(임시 컨테이너) | `ReservationPreparationConcurrencyIntegrationTest` 3건 전부 | 3건 모두 `expected: 1L but was: 2L` — CREATE 중복 생성, JOIN 정원 초과, 동일 회원 중복 참여가 실제로 재현됨 |

**해석:** 4개 도메인 모두 "현재 방어를 없애면 실제로 이 코드베이스·이 테스트에서 실패가 재현된다"는 인과관계를 확인했다. 특히 환불 도메인은 단일 계층(Refund 락)만 제거해서는 재현되지 않고 두 번째 방어 계층(Participant 조건부 UPDATE)이 독립적으로 막아낸다는 것까지 확인했다 — 이는 방어가 이중화돼 있다는 추가로 유의미한 발견이다.

## 3. 재검토한 잠재 위험 — Outbox Kafka 발행 자체의 멱등 키 부재

조사 중 "Outbox Kafka Publisher 자체에는 발행 멱등 키가 없다"는 사실을 발견했으나, 재검토 결과 **결함이 아니라 정상 설계**로 판단한다.

- Producer가 `complete` 반영 전 재시작해 동일 `CHAT_MESSAGE_CREATED` 이벤트를 재발행해도, Consumer 쪽(AI Moderation)이 이미 `messageId` 기준 `isCompleted()` 가드 + `@Version`으로 중복을 멱등하게 처리함(2.3의 테스트로 확인).
- 이는 Issue #60 본문이 목표로 제시하는 "at-least-once Producer + 멱등 Consumer" 조합 그대로다.
- Email(`EmailOutboxDelivery` UNIQUE + 조건부 `markSent`)과 ChatRoom(`chat_room.reservation_id` UNIQUE) 경로도 각각 별도 멱등 장치가 있어 동일하게 안전하다.
- Issue #192(Web/AI Worker 프로세스 분리)는 이 문제를 다루지 않으며, 오히려 "Retry/DLT·messageId 멱등 계약이 분리 후에도 유지된다"는 기존 계약을 전제로 한다 — 즉 #192와 #60의 이 발견은 서로 다른 관심사다.

## 4. BobFull Concurrency Decision Matrix

| 경쟁 유형 | 실제 BobFull 사례 | 실패 형태 | 비교 전략 | 최종 선택 | 선택 근거 | 재검토 조건 |
|---|---|---|---|---|---|---|
| Hot Row 정합성 | 예약 좌석 CREATE/JOIN | 초과 예약 | 비관적 락(현재) vs 낙관적 락/CAS | **비관적 락 유지** | 이미 구현·실제 MySQL 테스트 3/3 PASS, ADR-0001에 근거 문서화 | 인기 회차 부하(#142)에서 lock wait가 실제 병목으로 확인되면 재검토 |
| 복수 완료 경로 | 환불 완료(즉시응답+웹훅+스케줄러) | 중복 상태 전이 | 엔티티 가드+조건부 UPDATE(현재) vs 추가 락 확대 | **현재 구조 유지** | 22개 테스트로 Terminal 상태 역행 차단 확인 | 신규 완료 경로 추가 시 재검토 |
| 동일 Row 동시 수정 | AI Moderation(messageId) | Lost Update / 상태 회귀 | 낙관적 락+상태가드(현재) vs 비관적 락 | **현재 구조 유지** | 15개 테스트로 SAFE/FLAGGED 확정 후 역행 차단 확인 | 충돌 빈도가 실측으로 급증하면 재검토 |
| at-least-once 이벤트 | Outbox(claim/complete) | 중복 처리 | 처리권 Claim(현재) vs 분산 락 | **현재 구조 유지** | 9개 테스트로 동시 claim 단일 선점 확인 | 다중 인스턴스 확장 시 재검토 |
| Kafka Producer 재발행 | Outbox→Kafka(ChatMessageCreated) | 중복 발행 | Producer 멱등 키 도입 vs 현재(Consumer 멱등에 위임) | **미도입 — Consumer 멱등으로 충분** | 재발행돼도 Consumer가 messageId 기준으로 안전하게 처리함을 확인(3절) | Consumer 멱등 계약이 깨지면 재검토 |

## 5. 정합성 판정

- 초과 예약, Lost Update, 중복 Side Effect, Terminal 상태 회귀 — 모두 **관측되지 않음**(0건, 4개 테스트 스위트 전건 PASS 기준)
- 성능 수치(lock wait, conflict/retry, DB Pool)는 이번 검증에서 별도로 측정하지 않음 → `NOT_MEASURED`

## 6. 검증 한계

- "기존 구현이 대상 경쟁을 통과시키는가"와 "보호장치를 제거하면 실제로 깨지는가"(2.5절) 둘 다 확인했다. 다만 2.5절 재현은 각 도메인당 가장 핵심적인 보호장치 하나(조합)만 제거해 확인한 것이라, 나머지 보조 방어장치(예: 환불의 엔티티 Terminal 상태 가드 자체, AI Moderation의 `isCompleted()` 가드 자체)를 단독으로 제거했을 때의 개별 실패 양상까지 전부 조합해 확인하지는 않았다.
- 환불/AI Moderation/Outbox 테스트는 H2(MySQL 모드)로 실행되어 실제 MySQL 락 대기·데드락 타이밍까지는 검증하지 못했다(AGENTS.md 원칙상 "H2 결과만으로 MySQL 락 동작을 확정하지 않는다" — 예약만 실제 MySQL로 검증, 나머지는 상태 전이/멱등 로직 검증에 한정).
- 아래 P1 항목은 이번 라운드에서 조사하지 않았다: 결제 완료 시 PortOne 외부 조회 중복(#143 연계), 식당 테이블 표시번호 할당, Cache Stampede.
- 다중 인스턴스 환경에서의 동작(여러 App 인스턴스가 동시에 Outbox를 claim하는 경우 등)은 코드 리뷰로는 원자적 UPDATE 기반이라 안전할 것으로 판단하나, 실제 다중 인스턴스 실행으로 검증하지 않았다.

## 7. 후속 Issue 연결

- #142(인기 회차 부하 검증): 이번 Decision Matrix의 "비관적 락 유지" 결론을 입력으로 사용
- #66/#143/#176/#183: 이번 라운드에서 구조 변경이 필요하다고 판단된 항목 없음(모두 현재 구조 유지)
- P1 항목(#143 PortOne 중복 조회, 테이블 표시번호, Cache Stampede)은 별도 후속 조사 필요

## 8. ADR 판단

- 이번 결과는 "새 전략 도입" 없이 "기존 구조가 실측으로 충분함"을 확인한 것이므로, 신규 ADR 작성보다는 기존 ADR-0001에 이번 실측 결과(실제 MySQL 3/3 PASS)를 근거로 보강하는 것을 제안한다. 최종 판단은 Human 결정 필요.
