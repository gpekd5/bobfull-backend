# Issue #170 — 다중 인스턴스 채팅 Redis Pub/Sub Evidence

## 검증 대상

`ChatMessage`를 DB에 저장·커밋한 뒤 Redis Pub/Sub으로 모든 애플리케이션 인스턴스에 전달하고, 각 인스턴스가 자신의 STOMP 세션으로 한 번 전달하는 경로다. AI Moderation의 Transactional Outbox→Kafka 경로는 별도이며 유지한다.

## 측정 계약

- Primary KPI: 서로 다른 인스턴스 A↔B의 N건 실시간 수신·누락·중복 수.
- Secondary KPI: DB commit→Redis publish→원격 STOMP 수신 latency, Redis publish 실패 수, subscriber 재연결 시간, messages/sec.
- 안전 확인: ChatMessage DB 저장 1건, Redis subscriber의 DB 재저장·재발행 0건, 로컬 직접 STOMP+Redis 이중 전달 0건, 기존 참여 권한 유지.

## 기준 코드

- Before SHA: `a467fd9` (Issue #170 시작 시 최신 `develop`)
- 구현 기준 SHA: `e90edaf5e3f7ee2d5247b5a5545bb2ccc400dcd7`
- 최종 Phase A 검증 SHA: `8de1b43feb0662a32f6ff86658fed9dff83ddf30`

## 환경·데이터·실행 조건

- Phase A: 로컬 Docker의 MySQL·Redis를 공유하는 Spring Boot App A(`8080`)·App B(`8081`)와 인증된 native STOMP client A/B를 기동한다. 별도 Redis listener A/B 자동 검증도 유지한다.
- Phase B: `NOT_RUN` — #169 또는 동등한 ALB/WSS 다중 EC2 환경이 아직 이 작업 트리에 제공되지 않았다.

## Before 결과

`a467fd9`의 Controller는 ChatMessage 저장 후 현재 인스턴스의 `SimpMessagingTemplate`에만 직접 발행한다. 다중 인스턴스 A→B 실측은 환경 부재로 `NOT_RUN`이며, 단일 Simple Broker 구조상 원격 인스턴스 세션을 알 수 없다는 코드 구조만 확인했다.

## 변경 내용

- DB 커밋 뒤 `bobfull:chat:messages`에 JSON payload를 한 번 발행한다.
- 모든 인스턴스는 Redis subscriber로 payload를 받아 자신의 `/sub/chat/rooms/{chatRoomId}`에만 전달한다.
- Controller의 직접 STOMP 발행을 제거해 발행 인스턴스도 Redis subscriber 경로를 한 번만 사용한다.
- Redis publish/subscribe 실패는 구조화 로그와 고정 enum 메트릭을 남기며 DB 메시지를 롤백하지 않는다.

## After 결과

| 지표·현상 | Before | After | 판정 |
|---|---|---|---|
| 저장 후 Redis payload 발행 | 없음 | 단위 테스트로 JSON/channel 확인 | PASS |
| 커밋 전·롤백 Redis 발행 | 해당 없음 | TransactionSynchronization 테스트에서 0회 | PASS |
| subscriber local STOMP 전달 | 없음 | destination 1회 단위 테스트 | PASS |
| Redis publish 실패 시 저장 경로 예외 전파 | 해당 없음 | publisher가 catch+metric | PASS |
| A→B / B→A Redis subscriber 수신·인스턴스별 1회 전달 | NOT_RUN | 실제 Redis와 독립 A/B listener container에서 방별 각각 1회 | PASS |
| 같은 인스턴스 local 전달 회귀·다른 방 destination 격리 | NOT_RUN | A/B 모두 `/sub/chat/rooms/10`, `/sub/chat/rooms/11` 각 1회 | PASS |
| 실제 App A:8080 ↔ App B:8081 STOMP client, ChatMessage DB 1건 | NOT_RUN | A→B·B→A 각 1회, 메시지별 DB 행 1건 | PASS |
| Redis 중단·복구, cursor N/N 복구, ALB/WSS 검증 | NOT_RUN | NOT_RUN | NOT_RUN |

## 정합성 회귀 검증

실행 명령:

```bash
./gradlew :test \
  --tests 'com.bobfull.chat.controller.ChatMessageControllerTest' \
  --tests 'com.bobfull.chat.service.ChatMessageCommandServiceTest' \
  --tests 'com.bobfull.chat.realtime.*' \
  --tests 'com.bobfull.outbox.service.ChatMessageOutboxSignalDispatcherTest' \
  --tests 'com.bobfull.outbox.service.ChatMessageOutboxProcessorIntegrationTest' \
  --rerun-tasks
```

결과: `BUILD SUCCESSFUL` (2026-08-12). 단일 인스턴스 자동 테스트는 Redis payload와 local STOMP 전달, Redis 장애가 요청 처리 전체로 번지지 않는지, Controller 직접 발행 제거를 확인한다. Human이 아래 단일 실행 명령으로 전체 build exit code 0을 확인했다. 이전 `NoSuchFileException`의 원인은 확정하지 않았으며, 아래 격리 실행에서는 재현되지 않았다.

```bash
./gradlew --stop
rm -rf build
./gradlew clean build --no-daemon --no-parallel --max-workers=1 --console=plain --stacktrace
```

결과: `BUILD SUCCESSFUL in 2m 1s`, `14 actionable tasks: 12 executed, 2 up-to-date` — 전체 clean build `PASS`.

추가 Phase A 실행 명령:

```bash
./gradlew :test --tests 'com.bobfull.chat.realtime.RedisChatCrossInstanceIntegrationTest' --rerun-tasks --no-daemon --console=plain
```

결과: `BUILD SUCCESSFUL` (2026-08-12, exit code 0). `redis:7-alpine` Testcontainers와 독립 listener container A/B가 동일 Redis channel을 구독한다. 두 방 메시지를 각각 발행해 A/B의 local STOMP destination 수신이 정확히 1회인지 검증한다.

실제 2-instance Phase A 실행 결과(2026-08-12):

- Docker network `bobfull-backend_default`에서 App A는 host `8080`, App B는 host `8081`로 기동했고 같은 MySQL·Redis를 사용했다.
- App A의 MEMBER(6)가 room 4에 `phase-a-from-a`를 SEND하면 App A/B 모두 messageId `17`을 정확히 1회 수신했다.
- App B의 MEMBER(7)가 같은 room 4에 `phase-a-from-b`를 SEND하면 App A/B 모두 messageId `18`을 정확히 1회 수신했다.
- App A가 room 5에 `phase-a-other-room`을 SEND하면 App A만 messageId `19`를 수신했고 App B는 수신하지 않았다.
- 공유 MySQL 조회에서 ChatMessage `17`, `18`, `19`는 각각 정확히 1행이었다. raw STOMP 수신 집합에서 room 4 messageId의 총 수신 수는 고유 ID 수의 정확히 두 배(A/B 각 1회)였다.

## 구조화 로그·메트릭

- 로그: `CHAT_REALTIME_PUBLISH_FAILED`, `CHAT_REALTIME_SUBSCRIBE_FAILED`는 `messageId`·`chatRoomId` 또는 예외 유형만 기록하며 token·Authorization·원문은 기록하지 않는다. subscription 연결 실패는 `RedisMessageListenerContainer.handleSubscriptionException`에서 기록한 뒤 기존 재연결 흐름을 계속 사용한다.
- 메트릭: `bobfull_business_events{event=CHAT_REALTIME_PUBLISH_FAILED|CHAT_REALTIME_SUBSCRIBE_FAILED}`. 메시지 ID를 label로 사용하지 않는다.

## 결과 해석

Phase A는 Redis Pub/Sub의 인스턴스 간 전달, 두 Spring Boot 프로세스의 인증 STOMP A↔B 전달, 방별 local STOMP 목적지 격리 및 ChatMessage 단일 저장을 검증한다. 유실률·성능 또는 운영 WSS 성공은 주장하지 않는다.

## 검증 한계

- #169 환경 부재로 서로 다른 EC2 A/B, ALB WSS, Redis 실제 재연결과 장애 중 cursor 복구는 `NOT_RUN`이다.
- 반복 N건 latency/throughput 측정은 `NOT_RUN`이다.
- Pub/Sub 단절 구간의 메시지는 재생되지 않으며 DB cursor 조회가 복구 경로다.

## 관련

- Issue: #170
- ADR: [ADR 0011](../../../../40-architecture/adr/0011-chat-redis-pubsub.md)
- AI Outbox/Kafka: [ADR 0010](../../../../40-architecture/adr/0010-chat-message-outbox-kafka-pipeline.md)
