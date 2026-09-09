# Issue #258 — Moderation Kafka Partition Key Evidence

## 최종 결정

`ADOPT_MESSAGE_ID_KEY`

현재 Moderation은 `messageId` 하나를 조회해 원문을 분석하고 같은 `messageId`에 결과를 저장하는 독립 작업이다. 같은 채팅방 또는 같은 발신자의 Moderation 완료 순서 보장 계약은 현재 Head와 Issue #258 STEP 0에서 확인되지 않았다. 따라서 production 기본 Kafka key를 `messageId`로 전환한다.

## 동일 조건 비교

- 기준: 2026-08-14 KST, Issue #258 작업 브랜치
- 환경: Testcontainers Kafka, Partition 3, Consumer concurrency 3, Fake AI latency 500ms
- workload: 같은 `chatRoomId`에서 메시지 30건
- 실행: `./gradlew kafkaEvidenceTest --tests 'com.bobfull.kafka.consumer.ChatModerationConsumerConcurrencyIntegrationTest.같은_채팅방_30건에서_messageId_key는_여러_Partition과_Consumer를_활용하고_결과를_각_messageId에_저장한다' --rerun-tasks -PshowTestOutput`

| Producer key | Partition별 메시지 건수 | 활성 Partition 수 | drain time | 처리량 |
|---|---:|---:|---:|---:|
| `chatRoomId` | `{0=30, 1=0, 2=0}` | 1 | 15.616s | 1.92 msg/s |
| `messageId` | `{0=14, 1=9, 2=7}` | 3 | 7.271s | 4.13 msg/s |

`messageId` key는 세 Partition 모두에 메시지를 분산했고, 동일 workload의 drain time을 약 53.4% 줄였다. 이 측정은 Consumer thread/instance별 처리 건수를 식별하지 않으므로 실제 작업 Consumer 수를 직접 주장하지 않는다. Async보다 빨라야 한다는 조건은 두지 않았다. 이 비교는 Kafka 유지 여부가 아니라 현재 Kafka 내부 key가 독립 Moderation 작업과 맞는지를 검증한다.

## 신뢰성·순서 계약 확인

- 30개 결과 모두 각 전송에서 받은 `messageId`의 `ChatModeration`에 `SAFE`로 저장됨을 Testcontainers 통합 테스트에서 확인했다. Consumer 완료 순서가 전송 순서와 달라도 결과 저장 대상은 event의 `messageId`다.
- 동일 `messageId` 중복 수신 시 AI 호출과 결과 저장은 한 번만 수행하는 기존 Testcontainers 테스트를 유지한다.
- Retry 성공, Retry 소진 후 DLT 이동 및 `ANALYSIS_FAILED` 기록, 실패 message가 정상 message 처리를 막지 않는 기존 Kafka 통합 Evidence는 key 변경과 독립적으로 유지된다.
- `ChatMessage`의 저장·전송 순서는 Kafka Moderation 완료 순서와 독립적이다. 실시간 전달은 Redis Pub/Sub, 영속·조회는 DB가 책임진다.

향후 Context가 필요해도 Kafka 소비 순서를 진실로 사용하지 않는다. 별도 Issue에서 DB `ChatMessage` 이력의 명시적인 정렬, 시간창, 동일 room/sender 조건을 계약으로 정의해야 한다. #251의 Recent Context v1/v2는 여전히 `MEASURED_AND_REJECTED` 상태다.

## 범위와 한계

- 이 Evidence는 경량 30건과 Fake AI만 대상으로 한다. 실제 OpenAI Provider·프로덕션 부하·Partition 증설은 검증하지 않았다.
- `chatRoomId + senderMemberId`는 현재 순서 요구가 없으므로 채택하지 않았다.
- DLT Replay 도구와 Recent Context 재도입은 이번 Issue 범위 밖이다.
