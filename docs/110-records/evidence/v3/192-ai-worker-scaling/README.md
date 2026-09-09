# Issue #192 — Kafka AI Consumer 독립 실행·확장 및 MSA 분리 판단 Evidence

> Partition key의 production 최종 결정은 후속 [Issue #258 Evidence](../258-moderation-partition-key/README.md)를 따른다. #192의 과거 방 단위 순서 보장 전제는 현재 Moderation 계약의 근거로 사용하지 않는다.

## 검증 대상

`ChatMessage` COMMIT → Outbox → Kafka(`bobfull.chat.message-created.v1`, Partition 3) → `ChatModerationConsumer` → `ChatModerationService`(#66) 처리 경계에서, Web/API와 AI Consumer가 같은 프로세스에서 실행되는 현재 구조(A안)가 충분한지, 아니면 Web/AI Worker 실행 역할 분리(B안)·Consumer Scale-out이 필요한지를 실측으로 판단한다.

- 대상 코드: `ChatModerationConsumer`, `ChatModerationService`, `AiModerationPort`/`SpringAiModerationAdapter`/`FakeAiModerationAdapter`, `ChatMessageOutboxProcessor`, `ChatMessageAsyncModerationDispatcher`
- 측정·재현 환경: 로컬 macOS, JUnit5 `@SpringBootTest`, H2(MySQL 모드), Kafka는 Testcontainers `confluentinc/cp-kafka:7.7.1`(KRaft 단일 노드). 실험 0/A/B/C/D 실측 완료(경량 부하, `FakeAiModerationAdapter` 사용). 실험 E는 A·C 결과로 합성 판단, F는 #59 기존 Evidence 재인용. 실제 OpenAI Provider·프로덕션 규모 부하는 미실측(아래 "검증 한계" 참고).
- 측정 커밋: `7802364` 이후 이번 실행에서 추가한 실측 코드 적용(아래 "실험 준비" 표)
- 검증 한계(공통): (1) 모든 AI 처리는 `FakeAiModerationAdapter`로 대체해 실제 OpenAI 지연·429·비용 변동성은 반영하지 않음 (2) 부하 규모가 경량(요청 15~30건, 동시 사용자 10명 수준)이라 프로덕션 피크 트래픽과는 다름 (3) CPU/Heap/DB Pool 등 실제 리소스 사용량은 측정하지 않고 지연시간·처리량·성공/실패 건수로 대체 측정함

이 문서의 수치는 모두 **실측 완료 후에만** 채운다. 미측정 항목은 "검증 예정"으로 남기며 임의 수치를 기록하지 않는다.

## 측정 계약

- Primary KPI: Consumer `1 → 2 → 3` 단계별 consume rate / peak Lag / Lag 회복 시간, Web/AI Worker 분리 전후 HTTP p95/Consumer Lag 비교
- Secondary KPI: AI 처리 p95/p99, Provider 429/5xx/timeout, Retry/DLT 건수, token/cost, Worker/Web CPU·Heap·Thread, DB Pool
- 안전 확인: Retry/DLT·messageId 멱등 계약 유지(중복 저장 0), Kafka/AI 장애 시 Web 채팅·Outbox 보존 계약 유지, Outbox Kafka Publisher는 항상 Web/Core에 유지

## Human 결정 반영 (2026-08-12)

- 분리 착수 기준: Lag 미회복(5분+) / AI 처리 p99 3초 반복 초과 / HTTP p95 20%+ 동반 악화 중 하나
- 분리 방식: B안(같은 코드베이스, 실행 역할 분리)부터. C안(MSA)은 미검토
- Scale-out 우선 기준: Consumer Lag 주신호 + Provider 429/AI p95 병행 확인
- MSA 판단: B안으로 충분하면 MSA 미도입을 정상 결론으로 허용

## Retry 증폭 확인 (2026-08-13, 설정값 확인)

Issue 본문의 우려대로 Kafka Retry(최대 3회) × Spring AI 내부 Retry가 곱해지면 메시지당 최대 30회까지 외부 AI 호출이 늘 수 있다. 현재 설정을 다시 확인한 결과:

- `application-prod.yml`: `spring.retry.max-attempts: 1`(Spring AI 내부 Retry 비활성 — 1회만 시도)
- `application-prod.yml`: `bobfull.kafka.chat-message.consumer-max-attempts: 3`(Kafka Consumer Retry, 최초 처리 포함 최대 3회)

메시지당 실제 최대 외부 AI 호출 수 = 3(Kafka) × 1(Spring AI) = **3회**(숨은 증폭 없음). 이 설정은 #59에서 이미 정해둔 값이며 이번 실행에서 변경하지 않았다. 실제 Provider 대상 재검증 시에도 이 두 값이 바뀌지 않았는지 함께 확인해야 한다.

## 실험 준비 — 이번 실행에서 추가한 기반 코드

| 항목 | 내용 |
|---|---|
| `FakeAiModerationAdapter` | `bobfull.ai.moderation.fake-enabled=true`일 때 활성화, `fake-latency-ms`로 AI 처리시간을 100ms/1s/3s 등 고정값으로 재현. `SpringAiModerationAdapter`와 상호 배타적(`ConditionalOnProperty`) |
| `ChatModerationConsumer` concurrency | `bobfull.kafka.chat-message.consumer-concurrency`(default 1)로 `@KafkaListener` 동시 처리 스레드 수를 조절 |
| `ChatMessageAsyncModerationDispatcher` | `bobfull.chat.moderation.async-baseline-enabled=true`일 때만 Bean 생성. Outbox/Kafka 없이 `ChatMessage` 커밋 직후 전용 스레드풀(`async-baseline-concurrency`, 기본 8)에 `ChatModerationService.analyze()`를 직접 제출. 재시도·DLT 없음(의도적) |

## 실험 0 — 왜 Outbox+Kafka인가: Async Baseline과 비교 (실측 완료)

hyeonseung-dev 리뷰(PR #243) 제안대로, "Kafka가 더 빠르다"가 아니라 **처리 경계로서 무엇이 다른지**를 같은 조건(Fake AI 지연 500ms, 메시지 30건)에서 비교했다.

### 비교 조건

- Baseline(Async): Outbox/Kafka 미사용. `ChatMessage` 커밋 후 `ChatMessageAsyncModerationDispatcher`(전용 스레드풀, concurrency=3)가 즉시 `analyze()` 직접 호출
- Kafka: 기존 #59 경로 그대로. `ChatMessage`+Outbox 커밋 → signal 즉시 발행 → Kafka(Partition 3, Consumer concurrency=3) → `ChatModerationConsumer` → `analyze()`
- 둘 다 `FakeAiModerationAdapter`(지연 500ms)로 Provider 변동성 제거, 30건 동일 유입

### 측정 결과 (`ChatMessageSendLatencyEvidenceTest`, `ChatModerationConsumerConcurrencyIntegrationTest`, 실제 테스트 실행)

| 지표 | Async Baseline | Kafka(같은 채팅방 1개로 몰림) | Kafka(3개 채팅방으로 분산) |
|---|---|---|---|
| send() p50 | **3ms** | **2~3ms** | (미측정, 별도 시나리오) |
| send() p95 | **7ms** | **4~8ms** | (미측정) |
| send() max | 13ms | 7~23ms | (미측정) |
| 30건 완료까지(drain time) | **5.2~5.5초** | **15.5초** | **10.7초** |

판정:
- **send() 응답성은 거의 같다.** 둘 다 AI 처리(500ms)를 커밋 후 비동기로 넘기므로, Kafka가 Async보다 웹 응답을 더 빠르게 만들어주는 것은 아니었다. 이 결과는 "Kafka를 쓰면 빨라진다"는 주장이 틀렸다는 것을 실측으로 보여준다 — Kafka의 가치는 응답 지연이 아니라 다른 곳에 있다.
- **완료 처리량(drain time)은 오히려 Async가 더 빨랐다.** 원인은 Kafka의 Partition key가 `chatRoomId`이기 때문이다: 같은 채팅방 메시지는 항상 같은 Partition에 몰려 Consumer 3개 중 1개만 실제로 일을 한다(단일 방 15.5초 ≈ 순차 처리 500ms×30 이론값과 거의 일치). 채팅방을 3개로 분산하면 10.7초로 줄지만, key 해시가 Partition 3개에 완벽히 균등 분배되지 않아 이론적 3배 단축(5초대)에는 못 미친다. **"Consumer 수만 늘리면 병렬 처리량이 그만큼 는다"는 가정이 항상 맞지 않음을 보여주는 실측 근거다** — 실제로는 채팅방(key) 분산도가 함께 필요하다.
- **신뢰성 차이가 핵심이다** (`ChatMessageAsyncModerationDispatcherReliabilityTest`, 실측): Async Baseline은 순수 인메모리 스레드풀 큐라서, 처리 중이 아닌(큐에 대기 중인) 작업은 프로세스가 죽는 순간 재시도 없이 그대로 유실된다(테스트에서 큐 대기 중 2건이 강제 종료 시 `analyze()` 호출 자체가 한 번도 일어나지 않은 채 사라짐을 확인). 반면 Kafka는 이미 #59 Evidence(`ChatModerationConsumerIntegrationTest`, `ChatModerationDltPublishFailureIntegrationTest`)에서 Consumer 중단·AI 반복 실패에도 이벤트가 브로커에 보존되고 재시작 후 처리를 이어가거나 DLT로 격리됨을 실측했다.

결론(2026-08-13 hyeonseung-dev 리뷰 반영 — 표현 정확화): **Kafka를 단건 latency 개선 때문에 채택한 것이 아니라, AI처럼 느리고 실패할 수 있는 후속 작업을 Web 요청 경로와 분리하고, 미처리 작업을 broker에 보존하면서 적체·재시도·실패 격리·복구·Consumer 확장이 가능한 처리 경계를 만들기 위해 채택했다.** 이번 실측에서 오히려 Async가 특정 조건(단일 채팅방 몰림)에서 완료 처리량이 더 빨랐던 것이 이 판단을 뒷받침한다 — 속도로는 Kafka를 정당화할 수 없기 때문이다.

이 프로젝트에서는 Kafka 단독이 아니라 **Transactional Outbox + Kafka 조합**이 핵심이다.
- Kafka 도달 전 장애(Outbox→Kafka 발행 실패) → Outbox에 PENDING으로 남아 재발행(#59 Evidence)
- Kafka 이후 Consumer 장애 → 이벤트가 Broker에 남아 재소비(실험 B, 유실 0건·복구 7.8초)
- AI 반복 실패 → Retry 후 DLT로 격리해 Consumer 재시작 후에도 다른 이벤트 처리를 막지 않음(실험 C)

Async Baseline에는 이 중 어느 것도 기본으로 없다(재시도 없음, DLT 없음, 큐 유실 위험, Consumer 프로세스 단위 확장 불가). **"재처리(DLT Replay)"는 별개다** — 현재 구현이 보장하는 것은 "Retry → DLT 격리 → Consumer 재시작 후 이어서 처리"까지이며, DLT에 격리된 이벤트를 다시 꺼내 재처리하는 관리자 도구는 아직 없다(실험 C 참고, 후속 개선사항). Consumer 수 확장(실험 D)도 Partition key 분산과 함께 봐야 한다는 것이 이번 실측에서 드러난 추가 발견이다.

## 실험 0-1 — Kafka Partition/Consumer 병렬성이 실제로 활용되는 조건 재검증 (2026-08-13, hyeonseung-dev 리뷰 요청, 실측 완료)

실험 0은 채팅방 3개로만 비교해 `chatRoomId` 3개가 Partition 3개에 균등 분배됐다는 보장이 없었다. 그래서 **채팅방을 최소 30개 이상으로 늘려 Partition 3개 / Consumer 3개가 실제로 모두 활용되는 조건**에서 Async concurrency=3과 다시 비교했다(Consumer 1→2→3 재측정은 실험 D에서 이미 완료했으므로 하지 않음).

### 비교 조건

- 메시지 300건, 채팅방 30개(방당 10건 균등 분산), Fake AI 지연 500ms 동일, Async concurrency=3 / Kafka Partition=3·Consumer=3 동일

### 측정 중 발견한 버그: Outbox Signal Dispatcher 큐 포화로 메시지가 영구 유실될 수 있음

300건을 빠르게 연속 `send()`하자 완료 건수가 260건대에서 멈추고 5분(300초)을 기다려도 더 늘지 않았다. 원인은 `ChatMessageOutboxSignalDispatcher`(커밋 후 Kafka 발행을 즉시 트리거하는 컴포넌트)가 스레드 2개·큐 용량 100(하드코딩)으로 고정돼 있어, 순간적으로 100건을 초과하는 signal이 몰리면 초과분을 **재시도 없이 그대로 버리기** 때문이다(`discardAndLog()`). 이 테스트 환경에서는 Outbox 스케줄러도 꺼져 있어(`outbox.chat-message.enabled=false`) 버려진 signal을 회수할 방법이 없어 해당 메시지는 **영구히 Kafka에 발행되지 않았다**.

- 재현: 300건 연속 `send()` → 완료 262/300에서 멈춤(300초 대기해도 증가 없음)
- 조치: 이 실험에서만 Outbox 스케줄러를 안전망으로 켬(`outbox.chat-message.enabled=true`, `fixed-delay=1000ms`) → 버려진 signal도 스케줄러가 최대 1초 뒤 재발행 → 300/300 정상 완료
- **이것은 이번 실행에서 코드를 고친 게 아니라 실측 중 발견한 기존 동작이다.** 프로덕션(`application-prod.yml`)은 이미 `outbox.chat-message.enabled`가 기본 `true`라 이 안전망이 항상 켜져 있지만, **버스트 상황에서 즉시 발행이 아니라 최대 스케줄러 주기(기본 5초)만큼 지연될 수 있다**는 것은 이번에 새로 확인된 사실이다. `QUEUE_CAPACITY=100`은 코드에 하드코딩돼 있어 설정으로 조절할 수 없다 — 버스트 규모가 커지면 이 상수도 재검토 대상이 될 수 있다(이번 PR 범위 밖, 후속 개선사항으로 별도 기록 권장).

### 측정 결과 (`ChatModerationConsumerConcurrencyIntegrationTest`, `ChatMessageSendLatencyEvidenceTest`, 실제 테스트 실행, Outbox 스케줄러 안전망 활성화 상태)

| 지표 | Async Baseline(concurrency=3) | Kafka(Partition=3, Consumer=3, 30방 균등분산) |
|---|---|---|
| 300건 완료까지(drain time) | **50.9초** | **71.8~72.5초** |
| 처리량(messages/sec) | **5.90** | **4.14~4.18** |
| Partition별 메시지 분포(300건 중) | - | 예: `{P0: 140, P1: 100~110, P2: 50~60}`(실행마다 정확한 수치는 다르지만 항상 뚜렷하게 불균등) |

판정: **채팅방을 30개로 늘려 Partition 3개가 전부 사용되는 조건에서도 Async(50.9초)가 Kafka(71.8~72.5초)보다 빨랐다.** 원인은 Partition별 메시지 분포가 여전히 불균등하기 때문이다(가장 많이 받은 Partition이 가장 적게 받은 Partition의 약 2~3배). `chatRoomId` 문자열을 murmur2로 해싱해 3개 Partition에 배정하는 방식은, 키가 30개로 늘어나도 "균등 분배"를 보장하지 않는다 — 우연히 여러 방이 같은 Partition에 몰릴 수 있다. 전체 drain time은 평균이 아니라 **가장 많이 받은 Partition(가장 느린 Consumer)이 끝나는 시점**으로 결정되므로, Kafka의 실제 병렬 처리 이득은 Consumer 수보다 **Partition별 실제 분포**에 더 크게 좌우된다.

따라서 리뷰의 질문("충분히 많은 채팅방이 동시에 유입되어 Partition 3/Consumer 3 병렬성이 실제로 활용되는 상황에서 처리량이 어떻게 달라지는가")에 대한 답은: **여전히 Async가 더 빨랐다.** Kafka 우세로 뒤집히지 않았으므로, Kafka 채택 근거는 (실험 0 결론과 동일하게) 속도가 아니라 신뢰성·복구·격리·독립 확장 구조에 있다는 결론을 그대로 유지한다. 다만 이번 실험은 **Kafka Partition 기반 병렬성의 한계**(hash 분산의 불균등성이 Consumer 수 확장 효과를 갉아먹을 수 있음)를 규모를 키운 조건에서 한 번 더 확인해준 추가 근거다.

## 실험 0-2 — Partition key를 chatRoomId 대신 messageId로 바꾸면 더 빨라지는가 (2026-08-13, Human·hyeonseung-dev 논의 후 실측)

실험 0-1에서 "채팅방(key) 30개로도 Partition 분산이 불균등했다"는 결과를 보고, "그건 chatRoomId가 30개뿐이라 그런 거고 messageId(300개, 메시지마다 고유)를 key로 쓰면 Kafka가 더 균등해지고 결국 Async보다 빨라지지 않겠냐"는 질문이 나왔다. **결론을 먼저 말하면: 더 균등해지고 더 빨라지지만, Async를 넘어서지는 못한다.**

이유는 이론적으로 이렇다. Async(스레드풀)는 "공유 작업 큐"라서 스레드가 노는 순간 바로 다음 작업을 가져간다 — 어떤 key를 쓰든 상관없이 항상 이론적 최적(300건/3스레드=정확히 50초)에 가깝다. Kafka는 "고정 배정"이라 한 번 어떤 Partition에 배정된 메시지는 그 Partition을 담당하는 Consumer만 처리하고, 다른 Consumer가 놀아도 넘겨줄 수 없다. 그래서 Partition별 분포가 완벽히 균등해야만 Async와 동률이 될 수 있고, 그보다 빨라질 수는 없다 — 이걸 실측으로 확인했다.

### 구현

`ChatMessageOutboxProcessor`에 `bobfull.kafka.chat-message.partition-key-strategy` 설정을 추가했다(`chat-room`(기본값, 운영 동작 불변) / `message-id`(실험용)). 운영 기본값은 바뀌지 않으며, 이 실험에서만 리플렉션으로 전략을 바꿔 측정했다.

### 측정 결과 (`ChatModerationConsumerConcurrencyIntegrationTest`, 300건·채팅방 30개, 그 외 조건 실험 0-1과 동일)

| 지표 | chatRoomId key(실험 0-1) | messageId key(이번 실험) | Async(참고) |
|---|---|---|---|
| 300건 완료까지(drain time) | 71.8~72.5초 | **61.0초** | 50.9초 |
| 처리량(messages/sec) | 4.14~4.18 | **4.92** | 5.90 |
| Partition별 분포(300건 중) | 예: `{140, 100~110, 50~60}` | 예: `{118, 95, 87}` | - |

판정: **messageId key로 바꾸면 분포가 더 균등해지고(최대/최소 비율 약 2.8배 → 약 1.4배) drain time도 줄었다(71.8~72.5초 → 61.0초). 하지만 Async(50.9초)보다는 여전히 느렸다.** 300개라는 충분히 많은 고유 key로도 murmur2 해시가 3개 Partition에 완벽히 균등 분배(100/100/100)되지는 않았다 — 이 정도의 잔여 편차(118 vs 87)는 유한한 샘플을 해싱할 때 통계적으로 자연스러운 변동이다. 반대로 Async는 이런 통계적 변동 자체가 없다(스레드가 노는 즉시 다음 일을 가져가므로 항상 결정론적으로 최적).

**질문에 대한 최종 답:** messageId key로 바꾸면 Kafka 처리량이 개선되는 것은 맞지만(약 15% 단축), 그 개선의 대가로 **같은 채팅방 메시지의 처리 순서 보장을 잃는다**(현재 `chatRoomId` key는 속도가 아니라 같은 방 메시지를 한 Consumer가 순서대로 처리하게 하기 위한 설계다). 이번 실측 결과로는 "속도 15% 개선"이 "방별 순서 보장을 포기"할 만한 근거가 되지 못한다고 판단한다. 운영 Partition key는 `chatRoomId`로 유지하는 것을 권장하며, 코드는 실험 전용 설정(`partition-key-strategy`, 기본값 유지)만 추가하고 운영 동작은 바꾸지 않았다.

## 실험 A — AI 지연이 채팅 처리에 전파되는가 (실측 완료)

`ChatModerationConsumerConcurrencyIntegrationTest`(동시 사용자 10명 × 2건, 채팅방 3개 분산, Consumer concurrency=3, `FakeAiModerationAdapter` latency만 변경).

**측정 계층 명확화(2026-08-13 리뷰 반영):** 아래 수치는 실제 HTTP/STOMP end-to-end 요청 지연이 아니라 `ChatMessageCommandService.send()` 호출 자체의 소요 시간(**Chat SEND application service p95**)이다. 실제 HTTP/STOMP p95는 이번 실험 대상이 아니며 #64 Prometheus/Grafana 운영 관측으로 별도 확인해야 한다.

| AI latency | Chat SEND application service p50 | Chat SEND application service p95 | Chat SEND application service max |
|---|---|---|---|
| 100ms | 13ms | 15ms | 15ms |
| 1s | 9ms | 12ms | 12ms |
| 3s | 12ms | 18ms | 18ms |

판정: **AI 처리 지연이 100ms→3s로 30배 늘어도 Chat SEND application service p95는 12~18ms 구간에 머물렀다.** 통합 프로세스라도 signal 디스패치(#59 MAJOR 수정)와 Kafka Consumer 스레드가 요청 스레드와 분리돼 있어 자원 경쟁이 이 계층에는 전파되지 않았다. 다만 이것이 실제 HTTP/STOMP end-to-end p95가 악화되지 않았다는 뜻은 아니다 — 그 지표는 #64 운영 관측 대상으로 별도 확인해야 한다. Kafka offset 기준 Consumer Lag과 실제 CPU/Thread/DB Pool 수치도 측정하지 않았고 완료 건수 기반 처리량(backlog/drain time)으로만 간접 확인했다(검증 한계 참고).

## 실험 B — Consumer 중단 중 적체와 복구 (실측 완료)

`ChatModerationConsumerConcurrencyIntegrationTest`(Consumer Container `stop()` → 15건 `send()` → `start()`).

| 지표 | 값 |
|---|---|
| produced | 15건 |
| peak backlog(중단 중 미처리) | 15건(100%, 중단 중 처리 0건 확인) |
| processed N/N | 15/15 |
| lost event | **0건** |
| recovery time(재개 후 15건 완료까지) | 7.8초 |

판정: Consumer를 완전히 멈춰도 이벤트는 Kafka에 보존되고, 재개 즉시 순서대로 이어서 처리해 유실 없이 복구됨을 실측으로 확인.

## 실험 C — 실패 격리 / Retry / DLT / 재처리 (부분 실측)

`ChatModerationConsumerConcurrencyIntegrationTest`(정상 15건 + `FakeAiModerationAdapter.FORCE_FAIL_MARKER`로 강제 실패 5건을 다른 채팅방에 동시 유입).

| 지표 | 값 |
|---|---|
| 정상 Event 처리 성공률 | **15/15(100%)** — 실패 이벤트와 같은 시간대에 유입돼도 영향 없음 |
| Retry 횟수(consumer-max-attempts) | 최초 처리 포함 최대 3회(기존 #59 설정 그대로, 이번 실행에서 변경 없음) |
| DLT 건수 | **5/5(100%)** — 강제 실패 5건 전부 재시도 소진 후 DLT로 격리, `ANALYSIS_FAILED` 기록 |
| DLT Replay 성공 건수 | **미구현** — Issue #192 코멘트에 "이번 V3 이후 개선사항"으로 명시된 DLT 관리자 Replay 도구가 아직 없어 측정 불가 |
| 최종 Moderation 누락/중복 | 0건(정상 15건 SAFE, 실패 5건 ANALYSIS_FAILED, 중복 저장 없음) |

판정: 실패 이벤트는 자신이 속한 Partition/채팅방 범위에서만 재시도·DLT로 격리되고, 다른 채팅방의 정상 이벤트 처리에는 영향을 주지 않음을 확인. DLT Replay는 도구 자체가 없어 이번에도 검증 불가 상태로 남음(기존 #59 Evidence와 동일한 한계).

## 실험 D — Partition 3 / Consumer 1 → 2 → 3 확장 (실측 완료)

`ChatModerationConsumerConcurrencyIntegrationTest`(같은 채팅방 3개에 30건 고정, Consumer Container `stop()`→`setConcurrency(n)`→`start()`로 재구성, Fake AI 지연 500ms 고정).

| Consumer 수 | drain time(30건) | consume rate |
|---|---|---|
| 1 | 15.4초 | 1.94건/초 |
| 2 | 15.5초 | 1.93건/초 |
| 3 | 10.4초 | 2.88건/초 |

판정: **1→2에서는 거의 개선이 없었고 2→3에서만 뚜렷하게 개선됐다.** 3개 채팅방 key가 Partition 3개에 반드시 고르게 분산되는 게 아니라서(해시 충돌 가능), Consumer 2개 구간에서는 한쪽 Consumer가 여전히 대부분의 메시지를 떠안은 것으로 보인다. **"Consumer 수를 늘리면 늘리는 만큼 처리량이 오른다"는 가정은 이번 실측에서 기각됐다** — Partition key(`chatRoomId`) 분산도가 함께 맞아야 실제 병렬 처리 효과가 난다. Provider 429/timeout·token/cost는 Fake AI라 측정 대상이 아님(실제 Provider 대상 재실측 필요).

## 실험 E — AI 장애 영향 분리 (실험 A·C 결과로 합성 판단, 별도 신규 테스트 없음)

전용 테스트를 새로 만들지 않고, 실험 A(Web은 AI 지연과 무관하게 항상 빠름)와 실험 C(강제 실패 5건이 정상 15건에 영향 없음, 재시도·DLT로 격리)를 합치면 "AI 장애가 Web/API 정상 동작을 막지 않는다"는 이슈의 요구 조건을 실측으로 충족한다. 다만 실제 OpenAI timeout/5xx/429 응답 코드별 세분화된 거동(예: 429만 별도로 급증하는 상황)은 Fake Adapter로 재현하지 않았으므로 검증 예정으로 남긴다.

## 실험 F — Kafka 장애 (신규 실측 없음, #59 기존 Evidence 재인용)

새 테스트를 만들지 않고 `docs/110-records/evidence/v3/59-kafka-ai-pipeline/README.md`의 기존 실측 결과를 그대로 인용한다(코드 변경 없어 재검증 불필요, `ChatMessageSendLatencyEvidenceTest` 전체 스위트에서 계속 통과 확인됨):

- Kafka에 전혀 도달할 수 없는 상태(`bootstrap-servers=localhost:59999`)에서도 `send()` p50 1ms/p95 4ms/max 30ms로 응답 — Web/실시간 채팅은 Kafka 장애와 무관하게 정상 동작
- Outbox→Kafka 발행 실패 1건 강제 → PENDING(attemptCount=1) 유지 → backoff 후 재처리 → 1/1 실제 broker 발행·COMPLETED로 복구

## 브로셔 대표 수치 후보

```text
Kafka vs Async Baseline — send() 응답성: 거의 동일(p95 4~8ms). Kafka가 더 빠르다는 주장은 실측으로 기각(실험 0)
Kafka vs Async Baseline — 완료 처리량(30건, AI 지연 500ms): Async 5.2~5.5초 vs Kafka 10.7~15.5초(Partition key 분산도에 좌우, 실험 0)
Kafka vs Async Baseline — 신뢰성: Async는 큐 대기 작업이 프로세스 종료 시 재시도 없이 유실, Kafka는 브로커 보존·재시작 복구(실험 0, #59 재확인)
AI 지연 100ms→3s(30배)에도 Chat SEND application service p95는 12~18ms로 거의 변화 없음(실험 A, 실제 HTTP/STOMP p95는 #64 운영 지표 별도 확인)
Consumer 중단 15건 적체 → 재개 후 유실 0건, 복구 7.8초(실험 B)
정상 15건은 실패 5건과 동시 유입에도 100% 성공, 실패 5건은 재시도 소진 후 5/5 DLT 격리(실험 C)
Consumer 1→2→3 확장 시 drain time 15.4초→15.5초→10.4초 — 2에서는 거의 개선 없었고 3에서만 개선(Partition key 분산도 영향, 실험 D)
채팅방 30개·300건으로 늘려 Partition 3개를 실제로 다 써도 Async(50.9초)가 Kafka(71.8~72.5초)보다 여전히 빠름 — Partition별 분포가 불균등(최대/최소 약 2~3배 차이)해서(실험 0-1)
```

## 최종 판정 (2026-08-13 Human 확정)

```text
통합 모놀리스 유지 (최종 확정)
```

근거(2026-08-13 리뷰 반영 — 실측 범위에 정확히 맞춘 표현): Human 결정 Q1의 분리 착수 기준 중 "AI 처리 p99 3초 반복 초과"는 이번 실측 범위에서 관찰되지 않았다. "Lag 미회복 5분+"와 "HTTP p95 20%+ 동반 악화"는 각각 아래처럼 실측 범위를 구분해서 읽어야 한다.

- **이번 실험에서는 Chat SEND application service p95 악화가 관찰되지 않았다**(AI 지연 100ms→3s에도 12~18ms 유지). 실제 HTTP/STOMP p95는 실험 대상이 아니었으므로 #64 운영 지표에서 재검토한다.
- **이번 실험에서는 미처리 대기 건수·전체 처리 시간·복구 시간으로 적체·복구 능력을 확인했다**(15건 적체 → 유실 0, 7.8초 복구). Kafka offset 기준 Consumer Lag 자체는 직접 측정하지 않았으므로, 실제 Lag 추이는 #64 운영 지표에서 재검토한다.

Kafka를 채택한 근거는 단건 latency 개선이 아니라, AI처럼 느리고 실패할 수 있는 후속 작업을 Web 요청 경로와 분리하고 미처리 작업을 broker에 보존하면서 적체·재시도·실패 격리·복구·Consumer 확장이 가능한 처리 경계를 Transactional Outbox와 함께 만들었다는 점이다(실험 0 결론 참고). Retry 증폭 우려도 현재 설정(3×1=3회)에서 해당하지 않음을 재확인했다. B안(Web/AI Worker 실행 역할 분리)과 C안(MSA)은 필요성이 확인되지 않아 이번 V3 범위에서 도입하지 않는다.

**측정 한계(계속 유효 — 남겨두는 이유는 아래에서 트리거로 재검토하기 위함):**
- 실제 OpenAI Provider가 아닌 `FakeAiModerationAdapter`로만 측정함 — Provider 429/Rate Limit·실제 지연 변동성은 미반영
- 부하 규모가 경량(요청 15~30건, 동시 10명)이라 프로덕션 피크 트래픽과는 다름
- CPU/Heap/DB Pool 등 실제 리소스 경쟁은 측정하지 않음(지연·처리량 결과로 간접 추정만 함)
- Worker Scale-out 시 Partition 수보다 Consumer를 늘렸을 때의 유휴 Consumer 문제, Rebalance 발생 빈도는 미실측

**재검토 트리거:** 위 한계가 실제 운영(#64 Prometheus/Grafana)에서 문제로 드러나거나, Human 결정 Q1 기준(Lag 미회복 5분+/AI p99 3초 반복/HTTP p95 20%+ 악화)이 실측되면 그때 아래를 재검토한다. 그 전까지는 별도 부하 재검증을 추가로 수행하지 않는다.

```text
실행 역할만 Web / AI Worker 분리 (B안)
MSA 후속 검토 (C안)
```
