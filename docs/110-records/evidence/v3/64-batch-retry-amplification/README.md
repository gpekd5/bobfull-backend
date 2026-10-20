# Issue #64 — Batch 공유 실패 Retry 및 recovery-only 검증

## 검증 대상과 판정

기존 Poll-Batch의 공유 Provider/전체 매핑 실패에서 뒤 record가 반복 요청에 참여하는 문제를,
현재 Consumer 할당 내 그룹 시도와 recovery-only 상태로 제한했다. **로컬 무과금 기능·장애 경계 PASS**.
운영 활성화, 실제 Token/비용 절감, AI 품질 또는 영속 호출 상한을 입증한 결과가 아니다.
Batch 기본 OFF, Prompt/Policy/Rule/Model·SDK Retry·운영 기본 max-attempts 값은 변경하지 않았다.

## 계약·기준 코드

- [Issue #64](https://github.com/gpekd5/bobfull-backend/issues/64), 상위 [#55](https://github.com/gpekd5/bobfull-backend/issues/55).
- [Before 원인/실제 재현](https://github.com/gpekd5/bobfull-backend/issues/64#issuecomment-6296332485).
- Human의 조건부 승인 대조 후 [최종 구현 계약](https://github.com/gpekd5/bobfull-backend/issues/64#issuecomment-6296565395).
- Before: develop `51557450d03b55165e9769b130615893a42dae6c`.
- After 실행: 같은 HEAD + 미커밋 #64 소스. 최종 보존 Commit에서 실행했다고 HEAD를 소급 교체하지 않는다.
  변경 Java 소스 해시와 실제 검증 집계는 [verification-results.json](verification-results.json)으로 추적한다.
  최종 Commit/CI/Review는 PR에 기록한다. 문서에 자기 자신의 Commit SHA를 삽입하지 않는다.
- 과거 #55/#59/#62의 Evidence/raw/해시를 수정하지 않는다. #65 Size/Time 비교는 설계 상태로 유지한다.

### 측정 계약

- Primary: 같은 지속 공유 장애의 논리 Provider 요청 수 및 메시지별 요청 참여 횟수.
- Secondary: DLT·최종 DB·offset, recovery 재시도 중 추가 Provider 호출, SDK HTTP attempt 구분.
- Guardrail: 미완료 offset 건너뛰기0, 그룹 밖 메시지 DLT0, 완료/Rule 재전송 방지, 항목 실패 indexed Retry, 개별 DB·중복 방어 보존.
- 장애를 SAFE로 대체하거나 Retry 기본값을 낮추는 방법은 사용하지 않는다.

## 원인과 구현

### Before

`ChatModerationBatchService`는 공유 요청 실패를 모든 target의 실패로 기록하지만,
가장 앞선 원본 poll index 하나만 `BatchListenerFailedException`으로 전달했다.
Spring Kafka4.1.0의 `FailedRecordTracker`는 head offset별 Retry를 추적한다.
앞 record가 DLT로 복구되면 남은 suffix에 새 head 예산이 적용돼 뒤 메시지가 다시 요청에 참여했다.
DB 완료 Skip은 SAFE/FLAGGED 결과가 저장된 경우의 방어이지 Provider 실패·외부 호출의 멱등성 보장이 아니다.

### After

| 책임 | 구현 위치/계약 |
|---|---|
| 실패 분류 | `BatchModerationException`에 전체 실패 index와 실제 요청 실패 그룹을 보존. 항목 Validation/저장 오류와 분리 |
| underfill Native | `BatchProviderRequestException`으로 Provider 구간만 구분. Validation/저장은 기존 indexed 실패 |
| 그룹 예산 | `ChatModerationBatchRetryProcessor`: Consumer thread별 T/P/offset 상태. 실제 실패 요청에 참여한 항목만 시도 소비 |
| poll 분리 | 같은 lineage의 현재 poll 구성원만 재처리. 미참여 항목 예산 소모/그룹 밖 새 메시지 병합 없음 |
| 소진·복구 | RETRY→RECOVERY→RECOVERED. 소진한 실패 항목만 기존 Recoverer 호출, 성공/Rule 완료는 제외 |
| recovery-only | DLT/최종 DB 실패 후 RECOVERY 유지. Provider 재분석 대신 Recoverer만 재시도 |
| ACK 전 재전달 | RECOVERED 표식 유지. 실제 committed offset이 해당 record를 넘은 뒤 정리 |
| lifecycle | partition revoke/lost 및 Consumer thread 종료 시 상태 제거. 다른 Consumer thread의 예산과 분리 |
| 항목 실패 | 기존 DefaultErrorHandler의 원본 index·prefix·Retry/DLT 유지 |

항목 indexed Retry도 소진되어 Recoverer에 진입하면 같은 RECOVERY/RECOVERED 표식을 설정한다.
이는 항목의 Retry 횟수·prefix commit을 바꾸지 않고, 복구 실패 때문에 Provider 단계로 되돌아가는 것을 막는다.
Native OFF의 ErrorHandler는 변경하지 않았다.

### 안전한 Offset 경계

Spring Kafka4.1.0 sources.jar의 `FailedBatchProcessor`, `FailedRecordTracker`,
`KafkaMessageListenerContainer`를 확인했다. 일반 예외의 fallback은 poll 전체 recoverer를 호출하며,
ErrorHandler 정상 반환 후 container가 ACK를 추가할 수 있다. 이를 그대로 재사용하지 않았다.

- 공유 Retry/Recovery 전용 예외는 `ChatModerationBatchErrorHandler`에서 partition별 poll 최초 offset으로 seek한다.
- handler가 예외를 재전파해 container의 후속 ACK를 막는다. 직접 성공 offset commit을 추가하지 않는다.
- 성공/Rule 형제도 보수적으로 재전달될 수 있지만 DB 완료 Skip을 사용한다.
- 항목별 실패/invalid event만 기존 indexed handler에 위임한다.
- index 없는 예상 밖 예외는 poll 전체 DLT fallback 대신 seek/rethrow한다. 유실 방지를 위한 fail-closed이며,
  원인이 해결되지 않으면 Lag/재처리가 지속될 수 있다. 이를 성공 복구라고 기록하지 않는다.
- DLT publish→개별 `recordFinalFailure` 성공까지 기존 복구 완료 조건을 유지한다.
  최종 DB 실패 후 같은 record의 DLT는 중복될 수 있으며, 완전히 복구된 형제는 현재 할당 내에서 재발행하지 않는다.

## 환경·Before/After 결과

- Testcontainers `confluentinc/cp-kafka:7.7.1`, H2 `MODE=MySQL`, 실제 Listener/Service/JPA.
- Kafka 통합 Provider는 Mockito Port Test Double이다. 실제 Fake Adapter Bean 조합은 별도 Context8건으로 회귀한다.
- 공통 장애 비교: 같은 기존 fixture, 5건, source partition0, `consumer-max-attempts=2`, backoff100ms,
  Batch/Native Port 모두 지속 실패. 합성 UUID 본문은 실행마다 다르며 장애는 본문과 무관하게 동일하게 주입한다.
- SDK/HTTP는 별도 loopback endpoint와 고정 가짜 인증 문자열만 사용한다. `.env`/실제 API Key를 읽지 않는다.

| 지표 | Before | After |
|---|---:|---:|
| Batch 논리 호출 | 8 | 2 |
| Native 논리 호출 | 2 | 0 |
| 총 논리 호출 | 10 | 2 |
| 마지막 메시지 요청 참여 | 10 | 2 |
| 모든 항목의 참여2회 검사 | Before에서는 마지막 참여만 assertion | After는 5건 각각2회 확인 |
| DLT | 5 | 5 |
| 최종 DB | 5건 ANALYSIS_FAILED | 5건 ANALYSIS_FAILED |
| 최종 source offset | 끝까지 commit | 끝까지 commit |

실제 After 출력:

```text
POLL_BATCH_COMMON_FAILURE_AFTER batch_calls=2 native_calls=0 last_record_participations=2 dlt=5
```

위 수치는 **논리 Port 호출/메시지 참여**다. HTTP10→2, Token/실제 청구 비용80% 감소라는 의미가 아니다.
성공 응답의 의미상 FN/FP/HIGH 하향은 이번 검증 대상이 아니며 Retry가 이를 해결하지 않는다.

## 장애·정합성 검증

| 검증 | 실제 확인한 결과 |
|---|---|
| 전체 ID 매핑 실패 | 2건/2회: Batch2/Native0, DLT2, 최종 ANALYSIS_FAILED/MODERATION_BATCH_MAPPING |
| 그룹 밖 새 메시지·Rule | 실패2건만 DLT. 뒤 정상 결과와 Rule 완료 보존, 정상 메시지 요청 참여1회 |
| 부분 Validation/저장 | 정상 형제 저장, 실패 원본 index·미완료 offset 보존. 기존 남은1건 Native 처리 |
| 공유 요청 회복 후 부분 Validation | Batch2 후 항목1건 Native1, DLT 추가0. 그룹 Retry와 indexed Retry 분리 |
| DLT send 첫 실패 | Provider2회 유지, DLT send3시도/실제 record2건. 실패 record DB 미기록·source offset 보존 후 복구 |
| DLT 성공 후 최종 DB 첫 실패 | Provider2회 유지, 실패 DB 메서드2회, DLT3건(첫 항목 중복1건). 최종 두 FAILED 및 뒤 정상 record SAFE |
| indexed Validation 소진 후 최종 DB 실패 | Batch1/Native1 유지, 최종 실패 DB 메서드2회/DLT2. 실패 항목 FAILED·형제 SAFE, offset 보존 후 완료 |
| 복구 완료 후 ACK 전 실패 | 재전달돼도 현재 할당에서 추가 Provider/DLT 없음 |
| 같은 poll의 복수 partition | 실제 poll이 두 partition을 포함했음을 검사. Batch+Native 참여를 합산해 항목별2회, partition별 DLT2/DB 및 offset 확인 |
| poll 분리·그룹 밖 입력 | 단위 테스트에서 같은 그룹 일부만 전달하고 새 항목을 함께 전달. 현재 참여 항목만 예산 소비·복구 |
| lifecycle·thread | 실제 stop/start에서 non-empty revoke/clearThreadState 호출. 단위에서 선택 partition 제거·thread별 상태 분리 |
| 중복·저장 충돌 | 기존 완료 event/저장 후 ACK 실패 Skip, 두 Consumer 및 H2 Unique/Version 경쟁 회귀 |
| SDK Retry | loopback 429→200: 논리1/HTTP2. 지속429 소진: fixture maxRetries3에서 논리1/HTTP4 |
| Bean/기본 OFF | 실제 Native/Fake × Batch OFF/ON4조합 Context8건 및 Feature Flag4건 회귀 |

복수 partition 계약 테스트는 두 초기 position을 명시한 전용 container와 test-only
`fetch.min.bytes=4096`, `fetch.max.wait.ms=500`으로 poll 모양을 제어한다.
실제 Kafka poll/Listener/ErrorHandler/ACK를 사용하지만 group 재균형 또는 성능 비교가 아니다.
다른 통합 fixture와 Production fetch/Retry 설정은 변경하지 않았다.

## 실행·검증

```bash
env -u OPENAI_API_KEY ./gradlew :test \
  --tests '*ChatModerationBatch*' --tests '*ChatModerationProviderContextTest' \
  --tests '*SpringAiModerationBatchAdapterTest' --tests '*ChatModerationServiceTest' \
  --tests '*ModerationRulePolicyTest' --tests '*ChatModerationFinalFailurePersistenceIntegrationTest' \
  --tests '*ChatModerationDltPublishFailureIntegrationTest' \
  --tests '*ChatMessageOutboxProcessorIntegrationTest' --console=plain --no-daemon

env -u OPENAI_API_KEY ./gradlew test build --console=plain --no-daemon
git diff --check
```

- 최종 관련 회귀: **95 tests / skipped0 / failures0 / errors0**, BUILD SUCCESSFUL in1m6s.
  Kafka18, Processor10, ErrorHandler2, Batch Service12, Consumer2, Flag4, Context8,
  Batch Adapter5, Native Service26, Rule4, 기존 Native DLT 경계2건, 기존 Outbox2건.
- 최신 전체 `test build`: **BUILD SUCCESSFUL in3m30s**, root1,161 tests / skipped86 / failures0 / errors0.
  Lambda11 tests / skipped0 / failures0 / errors0는 UP-TO-DATE. 합계 **1,172 tests / skipped86 / failures0 / errors0**.
  root는 재실행했으며 Lambda를 이번 실행에서 다시 수행했다고 표기하지 않는다.
  전용 `kafka-evidence` 부하 측정은 재실행하지 않았다. Snapshot은 실제 JUnit 집계/관측 출력/소스 SHA-256이며 로그 전체를 복제하지 않는다.
- 개발 중 Spy의 Bean 타입 선택 오류와 복수 partition fixture의 Native 성공 설정/poll 모양 가정으로
  테스트 실패가 있었다. 실제 Bean 이름 선택, 두 Provider의 동일 실패, 명시 초기 position/fetch 조건으로 수정했다.
  이를 Production 장애 또는 실패 실행 PASS로 기록하지 않는다. 마지막 관련95건은 수정 후 결과다.
- 중간 전체 실행(root1,159건/skip86)은 기존 Outbox2건이 PENDING으로 남아 실패했다.
  해당 클래스의 KafkaAdmin cluster/topic 구성 timeout 및 Topic metadata60초 timeout을 확인했다.
  Outbox 코드·설정은 변경하지 않고 격리한 위95건 실행에서 Outbox2건 PASS를 확인했다.
  환경/실행 순서에 따른 초기화 위험 후보이며 원인을 확정하거나 이 실패를 PASS로 바꾸지 않는다.
  이 중간 실행 후 indexed recovery-only 보완을 포함한 최신 전체 실행은 별도로 확인한다.

## 한계·트레이드오프

- 보장은 현재 Consumer thread/할당의 메모리 상태 범위다. JVM kill/restart, partition revoke/lost 후에는
  Provider 재호출·DLT 중복이 가능하다. 영속 전역 상한, 외부 exactly-once를 제공하지 않는다.
- 실제 처리 중 강제 kill/재균형 전체 행렬은 미수행. unit lifecycle와 실제 stop/start/두 Consumer 회귀를 그 보장으로 확대하지 않는다.
- 공유 실패에서는 poll 전체를 보수적으로 다시 읽어 정상 형제의 DB Skip/조회·지연이 늘 수 있다.
  상태가 있으면 actual committed offset 조회가 추가된다. 정상 상태의 성능 향상률을 측정한 PR이 아니다.
- 유효 응답 후 항목 실패는 indexed Retry로 전환된다. 서로 다른 실패 단계가 연속 발생하는 전체 작업의 호출 수를
  공유 요청 max-attempts 하나로 제한한다고 주장하지 않는다.
- `ANALYSIS_FAILED`는 기존 DB 완료 Skip(SAFE/FLAGGED)과 다르다. RECOVERED 표식 소실 후에는 다시 처리될 수 있다.
- 실제 MySQL 격리/잠금·실제 SDK 장애 분포·운영 비용·품질·활성화는 미검증이다. H2/Test Double 결과로 대체하지 않는다.
- Runtime metric은 요청/HTTP/메시지/재전달의 분모가 다르다. T/P/offset·messageId·원문을 metric tag에 추가하지 않았다.

## 관련

- 기존 [#59 Evidence](../55-ai-moderation-efficiency/kafka-poll-batch.md)
- 기존 [#62 로컬 충전율/E2E](../62-kafka-poll-batch-measurement/README.md)
- [#65 Size/Time 비교](https://github.com/gpekd5/bobfull-backend/issues/65): 이번 구현에 포함하지 않음
- 최종 PR/CI/최신 Head AI Review는 #64 댓글과 PR에 연결한다. Draft 유지, Merge하지 않는다.
