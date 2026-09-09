# Issue #59 — Outbox + Kafka AI Moderation Pipeline Evidence

## 검증 대상

ChatMessage 생성 → `OutboxEvent(CHAT_MESSAGE_CREATED)` → Kafka → `ChatModerationConsumer` → `ChatModerationService.analyze/recordFinalFailure`(#66) 전달 파이프라인.

- Before: `0850f63`(origin/develop, #59 착수 시점 — 이 파이프라인 자체가 없던 상태)
- After: `300ddcc`(리뷰 반영 완료 시점)
- 측정·재현 환경: 로컬 macOS, Docker, `confluentinc/cp-kafka:7.7.1`(Testcontainers), H2

## 측정 계약

- Primary KPI: Kafka/AI 장애 상황에서 ChatMessage·Outbox 보존 여부, 복구 후 처리 성공 여부, 중복 소비 시 결과 중복 생성 여부(N건 발생 → 몇 건 보존/복구/최종 처리됐는가)
- Secondary KPI: Chat SEND 지연시간(Kafka 상태와 무관하게 빨라야 함)
- 안전 확인: ChatMessage+Outbox 원자 저장, DLT 발행 실패 시 최종 실패를 확정하지 않음, payload/DLT에 채팅 원문 미포함

## 장애 주입 결과 (실제 테스트 실행, 임의 수치 없음)

| 시나리오 | 주입 | 보존/복구 | 중복·최종 결과 |
|---|---|---|---|
| Outbox→Kafka 발행 실패 (`ChatMessageOutboxProcessorIntegrationTest`) | 발행 1건 강제 실패 | Outbox PENDING(attemptCount=1) 유지 → backoff 후 재처리 → **1/1** 실제 broker 발행·COMPLETED | - |
| AI 일시 실패 (`ChatModerationConsumerIntegrationTest`) | AI 호출 1회 강제 실패 | Kafka 재시도로 2회차 성공 → **1/1** SAFE/FLAGGED 저장 | 중간에 `ANALYSIS_FAILED` 기록 **0건** |
| AI 반복 실패 | AI 호출 항상 실패 | 최대 시도(3회) 소진 → **3/3** 시도 후 DLT 이동 **1/1** | `recordFinalFailure` **1회**, `ANALYSIS_FAILED` **1/1** 기록 |
| 동일 이벤트 중복 발행(ACK 후 크래시 대체 검증) | 같은 이벤트 2회 발행 | Consumer 2회 수신 | AI 호출 **1회**, `ChatModeration` 중복 생성 **0건** |
| DLT 발행 자체 실패 (`ChatModerationDltPublishFailureIntegrationTest`) | DLT 발행 1건 강제 실패 | 원본 레코드 커밋되지 않음(재시도 유지) | `recordFinalFailure` 호출 **0회**(잘못된 최종 확정 없음) |
| eventVersion 계약 위반 | 잘못된 버전 이벤트 1건 | AI 호출 없이 즉시 DLT | `recordFinalFailure` **1/1**, AI 호출 **0회** |

## Chat SEND 지연시간 (`ChatMessageSendLatencyEvidenceTest`, 실측)

MAJOR 1 수정(signal 비동기 디스패치) 이후, Kafka에 전혀 도달할 수 없는 상태(`bootstrap-servers=localhost:59999`)에서 `ChatMessageCommandService.send()`를 50회 호출해 측정.

| 지표 | Before(수정 전 설계) | After(실측) |
|---|---|---|
| p50 | Kafka ACK 대기에 종속(최대 10s) | **1ms** |
| p95 | Kafka ACK 대기에 종속(최대 10s) | **4ms** |
| max | Kafka ACK 대기에 종속(최대 10s) | **30ms** |

Before 값은 재현 전 코드 구조(동기 `.get(ackTimeoutSeconds,...)`)에 근거한 이론적 상한이며, 리뷰(hyeonseung-dev, MAJOR 1)에서 지적된 실제 결함이었다. After는 수정 후 실측치다.

## Kafka/Outbox 설정 명시

- Topic: `bobfull.chat.message-created.v1`, DLT: `bobfull.chat.message-created.dlt.v1`, Partitions: 3(default), Replicas: 1(local)
- Consumer Group: `bobfull-chat-moderation`
- Kafka Consumer 재시도: 최초 처리 포함 최대 3회(`FixedBackOff`, backoff 1000ms 기본, Human 결정 Q1)
- Outbox→Kafka 발행 재시도: 최대 5회(기존 #176/#183 backoff 정책 재사용)
- Spring AI 내부 재시도: `max-attempts=1`(`application-local.yml.example`/`application-prod.yml` 확인) — Kafka 재시도와 곱해지는 증폭 방지

## 로그·메트릭 민감정보 미노출 확인

- `ChatMessageCreatedEvent`(Kafka payload)에는 `eventId`/`eventVersion`/`messageId`/`chatRoomId`/`occurredAt`만 있고 채팅 원문 필드가 없음(코드 구조로 보장, DLT도 동일 이벤트 구조 재사용).
- `BusinessMetricRecorder`는 고정된 `BusinessMetricEvent` enum만 라벨로 사용해 `messageId` 같은 고카디널리티 값을 Prometheus 라벨로 쓰지 않음(기존 컨벤션 유지).

## Kafka Micrometer 메트릭 노출 확인 (`ChatModerationConsumerIntegrationTest`)

메시지 처리 후 `MeterRegistry`를 조회해 `kafka.consumer.*`/`kafka.producer.*` 계열 메트릭(예: `records.lag`, `records.consumed`)이 **별도 코드 없이 자동 노출**됨을 확인했다. Consumer Lag 실측·알람·대시보드 시각화는 이번 PR 범위가 아니며 #64에서 진행한다 — 이번 확인은 "메트릭이 존재하는가"까지다.

## 전체 회귀

`./gradlew :test`(프로젝트 전체) 통과. 기존 outbox/chat/moderation 관련 테스트에 회귀 없음.

## 한계

- 실제 프로세스 kill을 통한 "ACK 후 완료 저장 전 종료" 재현은 하지 않았다. 대신 동일 이벤트 재발행 시나리오로 그 결과(중복 소비에도 안전함)를 검증했다.
- Consumer Lag의 실제 수치·회복 시간, 대량 처리량 등 운영 규모 성능은 측정하지 않았다(#192 범위) — 이번 확인은 메트릭 존재 여부까지다.
- Chat SEND 지연시간은 단일 로컬 프로세스·순차 50회 호출 기준이며, 동시 부하 상황의 p95/p99는 측정하지 않았다.
- 실제 OpenAI 호출 품질은 이번 검증 대상이 아니다(#66에서 별도 검증). 이 파이프라인 테스트는 `AiModerationPort`를 Fake로 대체해 결정적으로 검증했다.
- 로컬 단일 broker/단일 파티션 그룹 기준이며, 운영 Kafka Cluster HA는 검증하지 않았다.
