# Issue #59 — 기본 OFF Kafka poll-batch 통합 검증 (상위 #55)

최초 전체 작업 트리의 구현·검증 기록은 아래에 보존하고, PR #60 병합 후 #59만 분리한 검증은
`PR B 분리 및 독립 재검증` 절에서 구분한다. 과거 측정값을 현재 실행값으로 소급 교체하지 않는다.

## 목적과 측정 계약

기존 Native Moderation을 보존하면서, 한 Kafka poll의 Rule miss만 최대 10건으로 묶는 동기 처리의
Offset/부분 저장/Retry/DLT 경계를 Fake Provider로 검증한다. 모델 품질 또는 실서비스 효율 검증이 아니다.

- 최초 검증 기준 HEAD: `339423fbca943d578bf07793ff336490904290eb` + 당시 미커밋 구현/테스트.
- Primary: 미완료 source offset 건너뛰기 방지, 재전달 후 최종 DB 결과 보존.
- Secondary: 실제 Batch 크기/충전율, 메시지 생성부터 결과 저장까지 E2E, 논리 호출과 HTTP attempt.
- 안전 확인: 기존 Rule/Native/Retry/DLT 회귀, 메시지별 Token 복제 방지, 기본 OFF.
- 최초 검증 실행에서는 실제 OpenAI/SMTP 호출, AWS, schema 변경, Commit/Push/PR/Issue 변경 없음.
- 기존 Prompt A/B/C/D, Q1 및 독립 Micro-batching 평가 결과는 수정하지 않는다.

## 처리 구조와 제외 범위

```text
OFF: 기존 RECORD Listener → ChatModerationService.analyze → Rule → Native → 검증/저장
ON:  전용 BATCH Listener → 완료 Skip/중복 제거 → 동일 단일/Split Rule
     → Rule miss 1건: 기존 Native Adapter
     → Rule miss 2~10건: 기존 Batch Envelope → ID 검증 → 항목별 검증/개별 저장
     → 미해결 항목의 원래 poll index로 BatchListenerFailedException
```

- 설정: `bobfull.kafka.chat-message.batch-enabled`, 기본 false. 환경변수 `KAFKA_CHAT_MESSAGE_BATCH_ENABLED`.
- ON/OFF Listener는 상호 배타적이며 기존 Moderation group/topic을 사용한다.
- 기존 Production System Prompt, Few-shot 15개, Policy v2, Rule, Model, SDK Retry는 변경하지 않는다.
- 기존 실험의 Batch 지시문/`items[{id,content}]`/`results[{id,moderation}]`를 사용한다.
- 미채택 Checklist/Q1을 사용하지 않는다. 외부 ID는 `m001` 같은 요청 내 중립 ID다.
- 출력 상한은 기존 단건 설정값(기본128) × 실제 Batch 항목 수다.
- Provider 호출 중 DB Transaction을 유지하지 않는다. 기존 메시지별 saveAndFlush 경계를 재사용한다.
- **poll-batch는 size/time 수집 계약이 아니다. 여러 poll을 합치거나 maxWait까지 기다리지 않는다.**
  poll 최대10건에서도 완료/Rule 제외 후 실제 LLM Batch는 작아질 수 있다. 한건이면 Native로 처리한다.
- Native 전환은 underfilled 1건 처리 규칙이다. 기술 오류를 이유로 자동 단건 Fallback을 확대하지 않는다.
- 영속 대기열, 신규 DB 상태/테이블, Blue-Green/AWS 변경 없음.

## Offset와 장애 계약

원래 poll 순서와 필터링 이후 LLM 순서를 별도로 보존한다. Rule/완료 결과를 제외한 배열 index를
Kafka 실패 index로 사용하지 않는다. invalid Event 앞의 유효 prefix도 처리한 뒤 실패를 전달한다.

매핑 전체가 불완전하면 결과를 저장하지 않는다. 매핑 정상 후 일부 Validation/DB 실패가 있으면
유효한 형제 항목은 저장하고, 가장 앞의 미해결 원본 record를 ErrorHandler에 전달한다.
뒤에 이미 저장된 항목이 재전달되면 DB 완료 결과를 Skip한다. 같은 partition의 미완료 offset을
성공 결과가 있다고 건너뛰어 commit하지 않는다.

Batch 전용 DefaultErrorHandler가 원래 record 단위 Retry 및 기존 DLT Recoverer를 사용한다.
Retry 소진 시 DLT 발행 후 recordFinalFailure가 성공해야 recovery가 완료된다.
DLT 발행 후 DB 실패 시 다시 전달되어 DLT가 중복될 수 있다. 외부 호출 exactly-once는 보장하지 않는다.

**공통 Provider 장애의 재참여 위험:** `max-attempts`는 ErrorHandler가 추적하는 실패 head record의
재시도 한도다. 공유 요청이 통째로 실패하면 앞 record를 DLT로 복구한 뒤 남은 record들이 다시
묶이므로, 뒤 record의 Provider 요청 참여 횟수를 같은 값으로 제한하지 못한다.
실제 Fake 5건/총2회 설정에서 Batch 요청8회 + 마지막 Native2회, 마지막 메시지 요청 참여10회,
최종 DLT5건을 확인했다. 실패를 SAFE로 바꾸거나 유실시키지는 않았지만, 장애 시 비용·지연·호출량
증폭 가능성이 있다. 이번에는 Retry/DLT 또는 Native Fallback 정책을 임의 변경하지 않았다.
지속 Provider 장애에 대한 중단/재개·예산·재시도 증폭 허용 기준은 활성화 전 별도 판단이 필요하다.

## 관측 계약

| 관측 | 기록 위치/의미 |
|---|---|
| `bobfull.ai.moderation.poll.size` | 전달된 poll의 레코드 수 |
| `bobfull.ai.moderation.batch.size` | Batch 요청 시작당 항목 수. summary count는 시도한 Batch 논리 호출 수 |
| `bobfull.ai.moderation.batch.fill.ratio` | 요청당 실제 항목 수 / 10 |
| `bobfull.ai.moderation.processing{path}` | rule/native/batch/skipped 처리 횟수. unique 메시지 수가 아님 |
| `bobfull.ai.moderation.e2e{path}` | ChatMessage.createdAt부터 저장 완료 후까지. histogram 포함, Offset Commit 시간 제외 |
| `bobfull.ai.moderation.batch.failure{stage}` | 요청/매핑 실패 또는 항목 검증/저장 실패 |
| 기존 route/final.status/failure | 기존 Rule/검증/저장 경계 재사용 |
| 기존 Kafka retry | Consumer thread에서 ErrorHandler.deliveryAttempt 조회. 재시도 성공도 집계 |
| 기존 DLT exhausted | 기존 Recoverer가 DLT와 최종 DB 기록 성공 후 집계 |
| `gen_ai.client.operation`, `gen_ai.client.token.usage` | Spring AI 자동 논리 호출/latency/Token, Batch 요청당 한 번 |
| `okhttp.requests` | 기존 SDK HTTP attempt/status 자동 계측 재사용 |
| `bobfull.ai.moderation.batch.cached.input.tokens` | 알려진 Batch cached input만 요청당 한 번 |
| `bobfull.ai.moderation.batch.estimated.cost.usd{basis}` | uncached와 cache_adjusted를 구분. 둘을 합산하면 안 됨 |

Batch Usage를 각 chat_moderation row에 복제하거나 임의 배분하지 않는다. Batch 결과 row의
prompt/completion/total_tokens는 NULL이다. Rule row의 NULL과는 provider로 구분한다.
따라서 기존 MySQL per-call Token 패널은 Batch 요청의 Token 내역을 나타내지 못한다.
Metric에 원문/messageId/eventId/memberId/chatRoomId/offset/중립 ID를 tag로 넣지 않는다.
중복 Consumer 실행에서는 Provider 호출이 반복돼도 최종 DB 결과는 한 건일 수 있다.
호출/Retry/Skip/최종 저장 건수를 분리해서 해석하며, Metric만으로 메시지별 중복 호출을 확정하지 않는다.

추정 비용은 [OpenAI 공식 GPT-4o-mini 단가](https://developers.openai.com/api/docs/models/gpt-4o-mini)의
Standard input $0.15 / cached input $0.075 / output $0.60 per 1M tokens 기준이다.
OpenAI 비동기 Batch API 할인이 아니며 실제 청구액이 아니다. Usage/Cache 미확인을 0으로 증명하지 않는다.
모델 단가 변경 시 갱신이 필요하며 현재 다른 모델 단가를 추정하지 않는다.

## 실행 환경 및 결과

- 실제 Kafka: Testcontainers `confluentinc/cp-kafka:7.7.1`, 검증 source/DLT 각각2 partitions.
- DB: 프로젝트 기존 테스트 방식의 H2 MySQL mode/JPA. 실제 MySQL locking/격리 검증으로 일반화하지 않는다.
- Provider: Port Test Double + 별도 loopback HTTP Fake. 유료 API/실제 사용자 데이터 사용 없음.
- API key는 실행 환경에서 제거하며 `.env`를 읽지 않는다.

```bash
env -u OPENAI_API_KEY ./gradlew :test \
  --tests '*ChatModerationBatch*' \
  --tests '*SpringAiModerationBatchAdapterTest' \
  --tests '*ChatModerationServiceTest' --console=plain
env -u OPENAI_API_KEY ./gradlew test build --console=plain
git diff --check
```

### 최초 전체 작업 트리 실행 결과

2026-10-20 KST 로컬 실행. 최종 전체 실행은 `BUILD SUCCESSFUL in 3m 2s`였다.

| 검증 | 결과 |
|---|---|
| `ChatModerationBatchServiceTest` | 12 PASS: Rule/Split/완료 Skip, Native 선택, 중립 ID 역순 매핑, 누락/중복/미요청 ID, Validation, fail-open, tag 제한 |
| `ChatModerationBatchConsumerTest` | 2 PASS: 필터링 전 poll index 및 invalid Event 앞 prefix 처리 |
| `ChatModerationBatchFeatureFlagTest` | 4 PASS: 기본 OFF/명시적 OFF/ON/Consumer OFF의 상호 배타적 Listener 선택 |
| `SpringAiModerationBatchAdapterTest` | 4 PASS: Production Prompt 원문/기존 계약, Schema/cap, Parse/잘림, SDK 429 Retry 및 자동 Metric |
| `ChatModerationBatchIntegrationTest` | 11 PASS: 실제 Kafka + H2/JPA에서 아래 장애 경계 검증 |
| 신규 테스트 합계 | 33 PASS, 실패0 |
| 전체 root | 1,118 tests / 86 skipped / failures0 / errors0 |
| lambda | 11 tests / skipped0 / failures0 / errors0 |
| 전체 합계 | 1,129 tests / 86 skipped / failures0 / errors0 |
| `test build` | SUCCESS. lambda test는 선행 전체 실행에서 수행, 최종 동일 source 실행에서는 UP-TO-DATE |
| `git diff --check` 및 신규 파일 whitespace 검사 | PASS |

Kafka 통합 검증:

1. 10건 Burst: 실제 poll/Batch 10건, 충전율1.0, Batch 논리 호출1회, Native0회. 중복 Event 재전달은 추가 호출 없이 완료.
2. 단건 저유입: Native1회, Batch0회. 다음 poll을 모으지 않고 완료.
3. 일부 DB 저장 첫 실패: 실패 원본 offset을 넘지 않음을 재시도 저장 중 직접 확인. 뒤의 정상 항목은 이미 저장됨.
   재전달 시 완료 형제는 Skip, 남은1건만 Native, 재시도 저장 성공 후 최종 offset commit. Retry metric +1 확인.
4. 매핑 정상/일부 Validation 실패: 정상 항목 저장 보존, 실패 항목만 재처리 후 완료.
5. Provider 전체 실패: Retry 소진 후 각 원본의 DLT/ANALYSIS_FAILED 기록.
6. DLT 성공/최종 DB 첫 실패: 후속 재처리를 latch로 멈춘 동안 committed offset이 실패 원본을 넘지 않고
   최종 DB row가 아직 없음을 확인. 재개 후 DLT2건/recordFinalFailure2회, 실패 row와 뒤 정상 row 저장, 최종 commit.
7. Consumer stop/start: 중단 중10건 backlog 및 이후 단건3회 유입을 복구. 앞 Burst까지 총23건 소진.
8. 결과 저장 후 ACK 전 Listener 실패 주입: 재전달되지만 완료 결과 Skip, 추가 Provider 호출 없음.
9. 동일 그룹의 두 Consumer: 서로 다른 partition에 중복 Event를 전송, 실제 동시 Provider 처리 후 DB는 메시지별1건.
   해당 실행은 poll이 단건으로 나뉘어 Native4회/Batch0회였다. 이를 Batch 동시 호출 검증이라고 확대하지 않는다.
10. 별도 동시 Batch 처리: 두 thread가 같은 두 메시지를 Batch 요청으로 각각 분석. 실제 H2 UNIQUE 충돌을 처리하고
    두 요청 모두 종료, 최종 DB2건. 이는 Kafka Consumer 재균형과 구분하는 DB 경계 검증이다.
11. 공통 Provider 장애5건: Batch8회 + Native2회 / 마지막 항목 요청 참여10회 / DLT5건. 위 재참여 위험 확인.

Loopback HTTP 검증에서 정상 Batch의 fixture Usage는 input100/output20/total120이다.
`gen_ai.client.operation` count1 및 input Token100을 확인했다. 429→200도 논리 호출1 / 실제 HTTP2 /
`okhttp.requests` timer count 합계2이며 성공 Usage/Estimated Cost를 복제하지 않았다.
fixture의 할인 미반영 Estimated Cost는 $0.000027이다. 실제 Provider 청구나 Token 절감 측정값이 아니다.

최종 Kafka 통합 suite의 저장 완료 E2E 누적 샘플:

| path | 관측 count | total | avg | max |
|---|---:|---:|---:|---:|
| native | 10 | 3,355.704ms | 335.570ms | 617.524ms |
| batch | 42 | 5,008.671ms | 119.254ms | 165.210ms |

이는 2026-10-20T12:28:07.995Z 시작 suite의 다양한 실패/재시작/중복 시나리오가 섞인 **Fake 누적 샘플**이다.
동일 부하의 Native/Batch Before/After가 아니므로 개선율이나 Production p95로 해석하지 않는다.
실제 OpenAI 호출 수/추가 API 비용은0이다.

### 구현 중 발견한 검증 오류와 조정

- Spring Kafka4.1.0 factory의 template ContainerProperties에만 Consumer properties를 넣으면 실제 생성
  container에 복사되지 않았다(실제 max.poll.records=500 관측). 전용 ContainerCustomizer에서 설정하도록
  수정하고, 실제 Consumer max.poll.records=10을 재검증했다. 공유 ConsumerFactory는 변경하지 않았다.
- 초기 Repository Spy 장애 주입의 callRealMethod가 Spring Data interface abstract method를 호출하는
  테스트 오류를 수정했다. 성공 경로는 원래 Repository proxy delegate를 호출해 실제 JPA 저장을 유지했다.
- Bean Mock 재설정 시 기존 Answer를 실행한 테스트 준비 오류를 doAnswer/doThrow 방식으로 수정했다.
- root 대상 필터를 전체 `test` task에 전달하면 lambda에 일치 테스트가 없어 실패하므로 targeted는 `:test`를 사용했다.
  이후 전체 root/lambda 회귀 및 build를 실제 실행해 성공을 확인했다.
- 위 초기 실패를 Production 결함이나 성공 결과로 혼동하지 않는다. 최종 결과는 수정 후 실제 실행 기준이다.

### 변경 파일

기존 수정3개:

- `src/main/java/com/bobfull/chat/application/service/ChatModerationService.java`
- `src/main/java/com/bobfull/chat/infrastructure/kafka/ChatModerationConsumer.java`
- `src/main/resources/application.yml`

신규 Production7개:

- `application/exception/BatchModerationException.java`
- `application/port/AiModerationBatchPort.java`
- `application/service/ChatModerationBatchService.java`
- `infrastructure/ai/SpringAiModerationBatchAdapter.java`
- `infrastructure/kafka/ChatModerationBatchConfig.java`
- `infrastructure/kafka/ChatModerationBatchConsumer.java`
- `infrastructure/metrics/ChatModerationBatchMetrics.java`

위 경로의 공통 prefix는 `src/main/java/com/bobfull/chat/`이다.
신규 테스트5개는 위 결과 표의 클래스이며, 본 Evidence1개를 추가했다.
기존 미커밋 실험 Java/Evidence는 수정하지 않았다. 기존 파일1,112개의 해시 대조에서 위 기존 수정3개 외
변경/삭제가 없었다. Prompt/Policy/Rule 원문 및 기존 Evidence 해시는 그대로 유지됐다.

## 한계 및 다음 판단

- 정상 SAFE 응답의 의미상 FN은 Validation/Retry/DLT가 검출하지 못한다. 현재 관리자 조회도 SAFE 전건 검토가 아니다.
- 실제 OpenAI 품질·latency·절감률을 새로 측정하지 않았다. Fake Token/지연은 Provider 효과의 증거가 아니다.
- Consumer stop/start와 동시 Consumer 검증은 실제 JVM kill/Blue-Green 배포 검증이 아니다.
- 여러 채팅방의 본문을 한 요청에 넣는 개인정보 경계는 활성화 전 Human 판단이 필요하다.
- 실제 Broker의 max.poll.interval, Provider timeout/SDK Retry를 포함한 처리 시간 상한과 종료 유예시간은 활성화 전 점검한다.
- 재시도/완료 Skip으로 Batch 구성이 달라지거나 1건 Native가 선택되면 분류 결과도 달라질 수 있다.
- 품질 허용 기준, 신규 FN/FP/HIGH 하향 중단 기준, Human 표본 검토, 실제 트래픽 충전율은 별도 승인/측정 대상이다.
- 이번 결과만으로 Production 활성화 또는 기존 독립 실험의 Token 절감률을 운영 효과로 주장하지 않는다.

## PR B 분리 및 독립 재검증

### 기준과 변경 범위

- PR #60: CI 성공 후 develop Merge `de75489a5dbe2f20f8328cb312098048a204b72a`.
- 원본 보존 Commit: `b87a689dc4042a36b626a5a40f721dfdf24b0e55`.
  기존 `feature/55-ai-moderation-efficiency` 브랜치와 Commit을 삭제하거나 재작성하지 않았다.
- 분리 브랜치: `feature/59-kafka-poll-batch`, 최신 develop에서 #59만 cherry-pick.
- 적용 Commit: `46814e4903056fbfaf69f99d49075979c6561313`.
- 이 분리 시점의 develop 대비 범위는 Production/설정10개, 테스트5개, 본 Evidence1개로 총16개다.
  PR #60의 실험 코드·raw JSON·Prompt/GT Evidence는 중복 Diff에 포함하지 않는다.
- 원본 #59 구현에서 Production 동작을 추가 변경하지 않았다. Batch 기본 OFF를 유지한다.
  현재 보존 Head는 Git 이력과 PR 본문에 기록하며, 최초 실행 HEAD를 덮어쓰지 않는다.

### Adapter 테스트 의존성 정리

`SpringAiModerationBatchAdapterTest`가 실험용 `Issue55MicroBatchEvaluation.BATCH_INSTRUCTIONS`를
참조하던 부분을 테스트 내부의 고정 `EXPECTED_BATCH_INSTRUCTIONS` fixture로 교체했다.
Production 상수를 그대로 참조하는 자기 검증으로 바꾸지 않고, 실제 HTTP 요청의 System 내용이
기존 Production Prompt + 승인된 Batch 지시문과 정확히 같은지 계속 검사한다.

- 고정 fixture / Production Batch 지시문 / 과거 실험 지시문 문자열의 동일성을 확인했다.
- Batch 지시문 UTF-8 SHA-256: `e1e45e40ced16d3ed78013364838e513012f16e529e98592e5bb6882360294c6`.
- Prompt/Policy/Few-shot/Schema/Output cap/Retry 동작 변경은 없다.
- 테스트의 실험 클래스 참조만 제거했으며, 공통 평가 클래스를 복제하거나 PR B에 재제출하지 않았다.

### 독립 targeted 검증

```bash
env -u OPENAI_API_KEY ./gradlew :compileJava :compileTestJava :test \
  --tests '*ChatModerationBatch*' \
  --tests '*SpringAiModerationBatchAdapterTest' \
  --tests '*ChatModerationServiceTest' \
  --tests '*ModerationRulePolicyTest' \
  --tests '*ChatModerationFinalFailurePersistenceIntegrationTest' --console=plain
```

2026-10-20 KST 실행: compileJava/compileTestJava 및 **64 tests / skipped0 / failures0 / errors0 PASS**,
`BUILD SUCCESSFUL in 49s`. #59 테스트33건과 기존 Native26건, Rule4건, #46 DLT/DB 경계1건을 포함한다.
실제 Kafka 통합11건은 Testcontainers `cp-kafka:7.7.1` + H2/JPA + Port Test Double로 실행했다.
HTTP Adapter4건은 loopback Fake endpoint를 사용했다. 실제 OpenAI 호출·추가 API 비용·AWS 작업은0이다.

재관측한 주요 경계:

- 실제 Consumer `max.poll.records=10`; 기본 OFF/명시적 OFF/ON/Consumer OFF Listener 선택 통과.
- Burst10건은 Batch1회, 충전율1.0. 저유입1건은 Native1회.
- 부분 저장/Validation 실패, 완료 Skip, 원본 poll index, Offset/Retry/DLT/최종 DB 경계 통과.
- Provider 공통 장애5건에서 Batch8회 + Native2회 / 마지막 항목 요청 참여10회 / DLT5건 재확인.
- 동시 Consumer 실행은 해당 실행에서 Batch0/Native4/최종 DB2건이었다.
  별도 두-thread Batch 저장 경쟁 테스트와 구분하며 Kafka Batch 동시 처리·재균형 전체 보장으로 확대하지 않는다.
- 성공 Usage와 추정 비용은 요청당1회이고, 메시지별 Batch Token은 NULL이다.
  SDK 429→200은 논리1/HTTP2이며 Token을 복제하지 않았다.

이 시점의 통합 테스트는 `AiModerationBatchPort`를 Test Double Bean으로 제공했다. 당시 production 소스에는
Fake Batch Adapter가 없었다. 후속 실제 Bean 조합 점검에서 Batch ON/Fake ON 기동 실패가 확인되었으며,
아래 `Fake ON + Batch ON 기동 결함 보완` 절에 원인·수정·Mock 없는 재검증을 별도로 기록한다.

### 독립 전체 회귀 및 build

```bash
env -u OPENAI_API_KEY ./gradlew test build --console=plain
git diff --check
git diff --check origin/develop...HEAD
```

같은 PR B production/test source에서 2026-10-20 KST 실제 실행했다.

| 검증 | 결과 |
|---|---|
| root | 1,118 tests / 86 skipped / failures0 / errors0 |
| lambda | 11 tests / skipped0 / failures0 / errors0, 동일 source의 기존 결과로 UP-TO-DATE |
| 전체 집계 | 1,129 tests / 86 skipped / failures0 / errors0 |
| 전체 build | BUILD SUCCESSFUL in 2m52s |
| Kafka 통합 테스트 | 11 tests / skipped0 / failures0 / errors0, 전체 회귀에서도 실제 실행 |
| whitespace 검사 | git diff --check 및 develop 대비 diff 검사 PASS |

이 집계는 최초 전체 작업 트리의 같은 숫자를 복사해 현재 검증으로 간주한 것이 아니라, PR #60 병합 후
#59만 적용하고 Adapter 테스트 의존성을 제거한 source에서 새로 실행한 결과다. Lambda 테스트만
UP-TO-DATE였으며 이를 이번 실행에서 다시 수행했다고 기록하지 않는다. 이번에도 유료 Provider 호출은0이다.
동시 Consumer 테스트는 이 전체 실행에서 Batch0/Native3/최종 DB2건이었다. targeted 실행의 Native4회와
다르지만 두 실행 모두 테스트의 외부 호출2~4회/최종 DB2건 계약을 통과했다. 경쟁 시 Provider 호출 수까지
고정되거나 exactly-once로 보장된다는 의미가 아니다.

관련: [독립 비교](microbatch-comparison.md), [Q1 미채택 결과](common-prompt-q1-diagnostic.md),
[PR #60](https://github.com/gpekd5/bobfull-backend/pull/60), [Issue #59](https://github.com/gpekd5/bobfull-backend/issues/59).

## Fake ON + Batch ON 기동 결함 보완

### Before와 원인

- Before Head: `bbaf76d4a37fd1f4e52b5f9363a33bb28b055fe6`.
- [기존 MAJOR Review](https://github.com/gpekd5/bobfull-backend/pull/61#issuecomment-6292731499)의
  production Bean 생성 진단에서 OFF/OFF, OFF/ON, ON/OFF는 기동했고 ON/ON은 실패했다.
- BatchService는 Batch ON이면 필수 `AiModerationBatchPort`를 요구한다. 실제 Batch Adapter는 Fake ON에서
  제외되지만 기존 Fake Adapter는 단건 Port만 제공하여 `NoSuchBeanDefinitionException`이 발생했다.
- 기존 FeatureFlagTest는 Service Mock, Kafka 통합 테스트는 Provider MockitoBean을 공급하므로
  이 필수 의존성 누락을 검출하지 못했다. 기존 PASS를 지우지 않되 Bean 조합 전체의 PASS로 해석하지 않는다.

### 최소 변경

- `FakeAiModerationBatchAdapter`만 추가한다. Batch ON/Fake ON에서만 등록되며 실제 Batch Adapter와 상호 배타적이다.
  기존 Native Fake와 실제 Native/Batch Adapter는 수정하지 않았다.
- 기존 `fake-result-type` SAFE/FLAGGED 계약을 유지한다: SAFE/빈 category/LOW 또는 FLAGGED/PROFANITY/HIGH,
  metadata는 Fake/fake-model이다. 중립 입력 ID를 그대로 반환하고 결과 수를 유지한다.
- `fake-latency-ms` 지연은 **Batch 요청당 한 번** 적용한다. 단건 Adapter를 N회 호출하지 않는다.
  한 항목이라도 기존 `FORCE_FAIL_MARKER`를 포함하면 요청 전체 실패를 전파한다. 인터럽트는 복원 후 실패시킨다.
- 실제 Batch와 같은 2~10건 경계다. Rule miss 1건 Native 분기나 Kafka Retry/DLT는 변경하지 않는다.
- Fake Batch는 Provider Usage/추정 비용을 만들어내지 않는다. 개별 DB 결과의 Token은 기존 Batch 저장 계약대로 NULL이다.

### Mock 없는 ApplicationContext 및 실제 Fake 처리

`ChatModerationProviderContextTest`는 Service/Port/Repository/ChatClient를 Mockito Mock으로 공급하지 않는다.
실제 `SpringAiModerationConfig`, Native/Batch Kafka Configuration, Recoverer, Service, Adapter, Metrics와
JPA Auditing을 등록하고 실제 Spring Data Repository + 격리 H2에 저장한다. Kafka AutoConfiguration이
실제 Listener endpoint를 등록하되 `auto-startup=false`로 Broker 소비는 시작하지 않는다.
ChatClient.Builder만 실제 SDK/OpenAiChatModel의 **loopback 전용** 클라이언트로 공급한다.
가짜 고정 인증 문자열만 사용하고 환경 API Key는 읽지 않는다. Fake 처리 후 loopback HTTP 요청0건을 검사한다.
따라서 전체 bootRun 또는 실제 OpenAI 인증/운영환경 검증이라고 확대하지 않는다.

| Batch | Fake | Native Port | Batch Port | Native/Batch Listener | Context |
|---|---|---|---|---|---|
| OFF | OFF | 실제 Native 1 | 0 (불필요) | 1 / 0 | PASS |
| OFF | ON | Native Fake 1 | 0 (불필요) | 1 / 0 | PASS |
| ON | OFF | 실제 Native 1 | 실제 Batch 1 | 0 / 1 | PASS |
| ON | ON | Native Fake 1 | Fake Batch 1 | 0 / 1 | PASS |

Batch ON에서도 남은 한건 처리에 Native Port가 필요하다. 각 Port의 실제/Fake가 동시에 등록되지 않는지
클래스별 Bean 수로 검사하며 Listener endpoint도 하나만 등록되는지 확인한다.

| 신규 테스트 | 결과·검증 범위 |
|---|---|
| FakeAiModerationBatchAdapterTest | 11 PASS: 2/10건 SAFE·FLAGGED ID/결과, 강제 실패, 0/1/11건 거절, 요청당 지연, 인터럽트 |
| ChatModerationProviderContextTest | 8 PASS: 4조합, 2/10건 실제 Service/JPA FLAGGED 저장·Token NULL·요청 metric1회, 강제 실패 전파/저장0 |

요청당 지연 테스트는 10건/설정200ms에 200ms 이상~1,500ms 이내 완료를 검사한다.
N회 단건 지연(2초) 회귀 방지용이며 처리량·운영 성능 개선의 근거가 아니다.
Fake 경로의 HTTP 요청은0이고 실제 OpenAI/AWS 작업은 없다. 기존 loopback wire 테스트는 별도로 유지한다.

### 실행 명령 및 관련 회귀

```bash
env -u OPENAI_API_KEY ./gradlew :compileJava :compileTestJava :test \
  --tests '*FakeAiModerationBatchAdapterTest' --tests '*ChatModerationProviderContextTest' \
  --tests '*ChatModerationBatch*' --tests '*SpringAiModerationBatchAdapterTest' \
  --tests '*ChatModerationServiceTest' --tests '*ModerationRulePolicyTest' \
  --tests '*ChatModerationFinalFailurePersistenceIntegrationTest' \
  --tests '*FakeAiModerationAdapterTest' --tests '*AiModerationPortSelectionTest' --console=plain
env -u OPENAI_API_KEY ./gradlew test build --console=plain
git diff --check
```

관련 검증은 2026-10-20 KST 실제 실행, **91 tests / skipped0 / failures0 / errors0**, BUILD SUCCESSFUL in 44s.
compileJava/compileTestJava 통과. 새19건 + 기존 Batch33건 + Native26/Rule4/DLT DB1/Fake5/Port 선택3건이다.
실제 Kafka Testcontainers 11건과 기존 DLT/DB 경계1건, loopback Batch Adapter4건을 포함한다.
개발 중 package-private Validator 직접 참조 및 Native Listener의 명시적 id를 가정한 테스트 오류가 있었다.
production 접근 범위/Listener id를 변경하지 않고, 실제 Service 경유 검증 및 자동 생성 endpoint id를 허용하는
assertion으로 수정했다. 이 초기 테스트 오류를 production 결함으로 기록하지 않는다.

전체 `test build`도 2026-10-20 KST **BUILD SUCCESSFUL in 3m18s**로 완료했다.

| 검증 | 이번 보완 후 결과 |
|---|---|
| root | 1,137 tests / 86 skipped / failures0 / errors0, 이번에 실제 실행 |
| lambda | 11 tests / skipped0 / failures0 / errors0, 기존 동일 source 결과 UP-TO-DATE |
| 합계 | 1,148 tests / 86 skipped / failures0 / errors0 |
| 신규 Context / Fake Batch | 8 / 11 PASS, 전체 회귀에서도 재실행 |
| Kafka Testcontainers | 11 PASS, 전체 회귀에서도 재실행 |
| 공통 장애 관측 | Batch8 + Native2 / 마지막 항목 참여10 / DLT5로 기존 증폭 재확인 |
| 동시 Consumer 관측 | Batch0 / Native4 / 최종 DB2건; Batch 동시 소비나 재균형 전체 보장과 구분 |
| whitespace | git diff --check PASS |

After source는 본 절과 신규3파일을 포함하는 수정 Commit으로 추적하며, 실제 최종 SHA/최신 CI/Review는
PR #61 본문과 Review에 기록한다. 자기 자신의 Commit SHA를 문서에 넣는 순환 갱신은 하지 않는다.
기존 독립 실험 raw JSON·측정값·Prompt/Policy/Rule/Model 및 Kafka Retry/DLT 설정은 변경하지 않는다.
위 운영 활성화 전 한계·과제는 그대로 남는다.
