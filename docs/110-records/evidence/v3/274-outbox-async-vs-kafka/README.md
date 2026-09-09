# Issue #274 — Outbox+Async vs Outbox+Kafka Evidence

## 1. 확인 질문

Transactional Outbox를 공통으로 둔 뒤, Outbox 이후의 Local Async와 Kafka가 내구성, 처리 성능, 실제 프로세스 복구에서 어떻게 다른지 확인한다.

## 2. 이전 비교의 문제

#192의 Memory Async 대 Transactional Outbox + Kafka 비교는 Outbox의 DB 내구성과 Kafka의 처리 경계 효과를 함께 비교했다. 따라서 “Async는 crash 시 유실되고 Kafka는 보존한다”는 해석으로 Kafka만의 효과를 분리할 수 없었다.

## 3. 비교한 구조

### Outbox + Local Async

ChatMessage와 OutboxEvent를 같은 트랜잭션에 저장한다. 커밋 뒤 Outbox processor가 row 처리권을 잡고, 크기가 제한된 local executor에서 moderation을 수행한 뒤 성공 시 `COMPLETED`로 전이한다. 프로세스 종료로 executor queue가 사라져도 DB의 `PROCESSING` row는 남으며, 운영 기준 stale threshold(약 5분) 뒤 scheduler가 다시 처리권을 잡는다.

### Outbox + Kafka

같은 Outbox row를 Kafka broker ACK 뒤 `COMPLETED`로 전이한다. `messageId` key의 record는 Consumer Group이 읽고 동일 moderation 경로를 호출한다. consumer가 종료되어도 broker에 처리 대기 record가 남고, 같은 group의 consumer가 다시 연결해 이어서 처리한다.

## 4. 환경

| 항목 | 처리 성능 | 실제 재시작 후 처리 재개 |
|---|---|---|
| BASE_SHA | `b0c387769f9c007ce6cda493709e83c8ef21feb7` | 동일 |
| Runtime | JDK 17, SpringBootTest | parent-owned Testcontainers + child Spring JVM |
| DB | H2 MySQL mode | parent-owned MySQL Testcontainer |
| Kafka | Kafka evidence test | parent-owned Kafka Testcontainer (Kafka scenario) |
| Workload | 30 messages, Fake AI 500ms | 30 messages, Fake AI 500ms |
| Concurrency | 3 | worker/consumer 3 |
| Kafka | partition 3, key `messageId` | partition 3, same Consumer Group |

Recovery에서 child #1만 실제 `destroyForcibly()`로 종료했다. MySQL과 Kafka broker는 parent가 유지했다.

## 5. 처리 성능 원본 실행

| Run | Outbox + Async drain | Outbox + Kafka drain |
|---|---:|---:|
| 1 | 5.394s | 7.210s |
| 2 | 5.345s | 7.201s |
| 3 | 5.394s | 7.309s |

요청 latency 단일 30-sample run의 raw 값은 Async p50/p95/p99 `3/5/200ms`, Kafka `1/2/3ms`였다. 반복 percentile 측정이 아니며 JIT, Spring 초기화, cold start 영향을 받을 수 있으므로 채택 근거로 사용하지 않는다.

## 6. 처리 성능 결과

| 지표 | Outbox + Async | Outbox + Kafka |
|---|---:|---:|
| Drain median | 5.394s | 7.210s |
| Throughput median | 5.56 msg/s | 4.16 msg/s |
| Normal lost | 0 | 0 |
| Normal duplicate | 0 | 0 |
| Kafka partition distribution | - | `{0=14, 1=9, 2=7}` (3 active) |

이 조건에서는 Async가 더 빨랐다. Kafka를 처리 속도 때문에 채택한다는 결론은 이 Evidence로 지지되지 않는다.

## 7. Async 실제 프로세스 재시작 후 처리

Crash 직전 snapshot은 `messages=30`, `moderations=3`, `PENDING=0`, `PROCESSING=27`, `COMPLETED=3`, `duplicate=0`이었다.

| Timeline | 값 |
|---|---:|
| restart → 첫 moderation 증가 | 296.825s |
| restart → Outbox COMPLETED 증가 | 296.838s |
| restart → 30건 완료 | 301.041s |

최종 snapshot은 `messages=30`, `moderations=30`, `PENDING=0`, `PROCESSING=0`, `COMPLETED=30`, `lost=0`, `duplicate=0`이었다. Local executor queue는 crash와 함께 사라질 수 있지만, claimed Outbox row가 MySQL에 남고 stale scheduler/reclaim 뒤 다시 처리됐다.

## 8. Kafka 실제 프로세스 재시작 후 처리

Crash 직전 snapshot은 `messages=30`, `moderations=3`, `PENDING=0`, `PROCESSING=0`, `COMPLETED=30`, `duplicate=0`이었다. 여기서 Outbox `COMPLETED=30`은 moderation 완료가 아니라 Kafka broker publish ACK 완료이며, broker에 consumer backlog가 남아 있었다.

| Timeline | 값 |
|---|---:|
| restart → 첫 moderation 증가 | 40.614s |
| restart → 30건 완료 | 47.035s |
| kill → 30건 완료 | 52.202s |

최종 snapshot은 `messages=30`, `moderations=30`, `COMPLETED=30`, `lost=0`, `duplicate=0`이었다. 이 수치는 Spring Boot 기동, consumer 초기화와 partition 배정을 포함해 재시작부터 처리 재개까지 전체 시간을 잰 값이다.

## 9. 재시작 후 처리 비교

| 항목 | Outbox + Async | Outbox + Kafka |
|---|---:|---:|
| restart → 처리 재개 | 296.825s | 40.614s |
| restart → 전체 완료 | 301.041s | 47.035s |
| crash lost | 0 | 0 |
| crash duplicate | 0 | 0 |
| 보존 경계 | DB Outbox | Kafka Broker |
| 재개 경계 | stale scheduler + reclaim | Consumer Group + backlog |

이번 실험에서 restart 후 처리 재개는 약 256.211초, 전체 완료는 약 254.006초 차이였다. 이는 Kafka의 단독 “복구시간”이 아니라 각 애플리케이션 재기동 경로를 포함한 비교값이다.

## 10. Transactional Outbox가 맡는 일

Transactional Outbox는 ChatMessage와 후속 작업 의도를 같은 트랜잭션에 저장한다. 동일 Outbox 조건에서는 Async와 Kafka 모두 정상 실행 및 실제 process crash 뒤 `lost=0`, `duplicate=0`으로 복구됐다. Kafka만이 작업 유실을 막는다는 결론은 성립하지 않는다.

## 11. Kafka가 추가로 맡는 일

Kafka는 Outbox 이후에 broker에 남은 대기 record, Consumer Group, consumer lag, partition 기반 병렬 처리, 독립 worker와 scale-out, retry/DLT 구조라는 운영 경계를 제공한다. 이번 crash run에서 Retry/DLT 강제 실패 주입은 수행하지 않았으므로 그 효과를 이 실험으로 검증했다고 주장하지 않는다.

## 12. #192 해석 정정

유지할 수 있는 것은 #192에서 실제 측정한 Memory Async baseline, Kafka workload, AI latency가 web send latency에 직접 전파되지 않았던 관측, Kafka crash/restart 시나리오의 raw 결과다. 그러나 Memory Async와 Outbox + Kafka를 비교해 “Async process crash는 유실, Kafka는 보존”이라고 일반화한 해석은 폐기하거나 수정해야 한다. #274는 같은 Outbox에서 양쪽 모두 `lost=0`, `duplicate=0`임을 보였고, 차이는 DB stale scheduler recovery와 broker/consumer recovery의 경계에 있었다.

## 13. 최종 판단

### B. KAFKA_JUSTIFIED_FOR_OPERABILITY

Outbox + Async도 작업 의도를 DB에 남겨 이번 부하 조건에서는 유실 없이 복구됐고, 처리 완료 시간도 더 빨랐다. 그럼에도 Kafka는 Consumer 이후의 적체, 복구, 관측, 독립 worker 운영과 확장을 DB stale scheduler가 아닌 broker/Consumer Group 경계로 분리한다. 따라서 Kafka의 유지 근거는 속도나 유일한 유실 방지가 아니라 운영 가능한 비동기 worker 경계다.

## 14. 한계

- 30-message 부하 조건과 Fake AI 500ms 조건의 결과다.
- 성능 결과는 H2(MySQL mode) 기반 테스트 환경이다.
- 재시작 후 처리 결과는 Testcontainers와 child JVM을 사용했다.
- restart 수치는 Spring 기동 등을 포함한 전체 시간이다.
- 실제 AWS multi-EC2 운영 recovery를 측정한 값은 아니다.
- #274에서는 Retry/DLT failure injection을 별도로 검증하지 않았다.
