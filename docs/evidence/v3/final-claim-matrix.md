# BobFull V3 최종 주장 연결표

## 목적

이 문서는 V3 최종 발표·README·포트폴리오에서 사용할 **핵심 개선 주장과 실제 Evidence를 한눈에 연결하는 인덱스**다.

숫자와 검증 현상의 원본은 각 `docs/evidence/v3/<issue>/README.md`이며, 이 문서에 원본 결과를 중복 저장하지 않는다.

```text
Issue
→ 실제 구현/PR
→ Evidence
→ 이 Matrix 핵심 요약
→ 최종 발표·README
```

## 기록 규칙

- 실제 측정한 값만 작성한다.
- 실행하지 않은 반복 실험의 성공률·유실률·개선율을 소급 생성하지 않는다.
- Before/After 조건이 다르면 직접 개선율을 계산하지 않는다.
- 성능이 아닌 신뢰성 문제는 `N건 중 복구 N건`, `중복 0건`, `PENDING 보존`처럼 검증 가능한 현상을 사용한다.
- `MERGED`와 `MEASURED`는 서로 다른 축이다. 구현이 develop에 Merge된 Issue도 정량 Evidence가 없으면 `Final Claim 상태 = MERGED`, `Evidence 수준 = NOT_MEASURED`일 수 있다.
- Evidence가 부족하면 발표 문구를 `구현함` 또는 `검토함` 수준으로 낮춘다.
- 실제 Evidence 경로가 없거나 저장소에서 확인되지 않는 Issue는 임의 경로를 만들지 않고 `Evidence = N/A`, `Evidence 수준 = NOT_MEASURED`로 둔다.

## Final Claim 상태

Issue #67의 최종 주장 검토 결과를 이 문서의 상태 기준으로 사용한다.

- `MERGED`: 실제 구현이 develop에 Merge 완료됨
- `MEASURED_AND_REJECTED`: 측정 결과 기술 도입 필요성이 없어 미채택
- `OPERATIONAL_BOUNDARY_ADOPTED`: 성능 우위 가설은 채택하지 않았지만, 운영·복구·격리 경계를 근거로 해당 기술 구조를 채택 또는 유지함
- `DEFERRED`: 프로젝트 범위에서 의도적으로 보류
- `NOT_VERIFIED`: 구현 또는 측정 일부는 존재하지만 최종 검증 근거 부족

## Evidence 수준

Evidence 수준은 구현·채택 상태가 아니라, 최종 주장에 연결할 Evidence의 검증 수준을 표시한다.

- `MEASURED`: 동일 조건 또는 명확한 실험 조건에서 정량 Evidence가 있음
- `VERIFIED`: 기능·장애·신뢰성·정합성 검증은 있으나 성능 수치가 핵심이 아님
- `NOT_MEASURED`: 최종 Evidence가 아직 없음

예를 들어 Outbox는 `Final Claim 상태 = MERGED`, `Evidence 수준 = VERIFIED`일 수 있고, 성능 개선은 `Final Claim 상태 = MERGED`, `Evidence 수준 = MEASURED`일 수 있다.

## V3 핵심 Claim Matrix

| Issue | 최종 주장 | Final Claim 상태 | Evidence 수준 | Primary KPI·현상 | Before | After | Evidence | 핵심 한계 |
|---|---|---|---|---|---|---|---|---|
| #176 ChatRoom Outbox | 결제 완료 핵심 트랜잭션과 ChatRoom 생성 의도를 함께 커밋하고, 실패한 후속 처리는 Outbox `PENDING`으로 복구한다. | `MERGED` | `VERIFIED` | 실패 후 `PENDING` 보존, 재처리 `COMPLETED`, 중복 처리 최종 1건, stale `PROCESSING` 회수 | V2 `AFTER_COMMIT` 실패 시 ChatRoom 0건·복구 Event 없음 | V3 Outbox `PENDING` 보존 후 Processor/Scheduler 재처리 | `176-chatroom-outbox/README.md` | JVM kill/restart 반복 통계와 운영자 수동 재처리 UI/API는 검증하지 않음 |
| #183 Email Outbox | 이메일 발송 의도와 수신자별 발송 상태를 Outbox/Delivery로 보존하고, 성공 수신자는 중복 발송하지 않는다. | `MERGED` | `VERIFIED` | 수신자별 `SENT` 보존, 실패 수신자만 `PENDING` 재시도, 공통 Outbox backoff/`FAILED` 사용 | V2 메모리 이벤트·부분 성공 영속 구분 없음 | V3 `OutboxEvent` + `email_outbox_delivery` | `183-email-outbox/README.md` | 외부 SMTP가 정확히 한 번만 보내는지는 범위 밖. 대량 SMTP 장애 실험도 수행하지 않음 |
| #59 Outbox + Kafka AI | ChatMessage 저장 뒤 AI Moderation 후속 처리를 Outbox→Kafka→Consumer로 분리하고 Retry/DLT로 실패를 격리한다. | `MERGED` | `VERIFIED` | 발행 실패 1/1 복구, AI 반복 실패 3/3 후 DLT 1/1, 중복 이벤트 AI 호출 1회·중복 저장 0건, Kafka 불가 시 send p95 4ms | 파이프라인 없음 또는 Kafka ACK 대기 구조 | Outbox 보존 + Kafka Retry/DLT + 요청 스레드 분리 | `59-kafka-ai-pipeline/README.md` | Consumer Lag·대량 처리량·운영 Kafka HA는 검증하지 않음 |
| #60 Concurrency Strategy | 예약·환불·AI Moderation·Outbox의 기존 동시성 방어가 현재 범위에서 충분해 새 전략을 도입하지 않았다. | `MERGED` | `VERIFIED` | 예약 MySQL 3/3 PASS, 환불 22/22 PASS, AI 15/15 PASS, Outbox 9/9 PASS; 보호장치 제거 시 실패 재현 | 전략 재설계 후보 존재 | 비관적 락·조건부 UPDATE·낙관락·Outbox claim 유지 | `60-concurrency-strategy/README.md` | 성능 지표(lock wait, DB Pool)는 별도 측정하지 않음 |
| #61 SQL / Index | 식당 date/time 검색의 `shared_table` full scan과 회차 조회 중복 쿼리를 측정 후 제거했다. | `MERGED` | `MEASURED` | date 11.5ms→3.03ms, time 12.1ms→1.33ms 대표 실행; 회차 20건 PreparedStatement 123→83 | `shared_table` full scan, TimeSlot당 중복 쿼리 2개 | `idx_shared_table_restaurant_id`, known participant count 재사용 | `61-search-query/README.md` | 대표 실행 1회·HTTP p95/p99 일반화 금지 |
| #62 Redis Search Cache | date/time 없는 식당 검색 반복 요청은 Redis Cache로 DB 조회와 Hikari Pool 점유를 우회한다. | `MERGED` | `MEASURED` | Warm hit DB query 0, 동시 30×5 active 10/10·awaiting 20 → active 0·awaiting 0, p95 43ms→14ms | 매 요청 DB content+count 조회 | TTL 60초, version namespace invalidation, Redis 장애 시 DB 조회로 대체 | `62-search-cache/README.md` | 실제 운영 반복률·Stampede·HTTP 장애 E2E는 미측정 |
| #65 Settlement | 지급 예정 금액 조회는 Snapshot/Batch 없이 Query/Index 개선으로 Load 수준 병목을 해소했다. | `MERGED` | `MEASURED` | 결합 p95 6.5s→30.32ms, p99 9.12s→91.69ms, Hikari pending 92→0 | `payment.time_slot_id` 인덱스 부재로 Pool 포화 | 인덱스 3건 추가, Query-time 계산 유지 | `65-settlement/README.md` | Stress 단계와 일부 인덱스의 AWS 재측정은 수행하지 않음 |
| #66 AI Moderation Core | Prompt drift를 탐지·복구하고, production 기본 모델은 `gpt-4o-mini`로 유지했다. | `MERGED` | `MEASURED` | Regression 40건 Review Actionability 65.0%→97.5%, Provider/parse failure 0; gpt-5.4-nano 결정적 우위 없음 | Prompt drift 상태 | Prompt v2 복구 baseline, 128 token guard, 모델 유지 | `66-ai-moderation/README.md` | Dataset 40건 기준선이며 전체 서비스 정확도 보장 아님 |
| #213 AI Moderation Held-out | Held-out/Challenge 재검증 뒤 경계 사례 완전 분류 대신 명백한 강한 욕설·개인정보·명시적 SPAM 중심으로 Scope를 확정했다. | `DEFERRED` | `MEASURED` | Held-out Result/Category 74/80(92.5%), Challenge 20/24(83.3%), SPAM boundary 과탐 확인 | Regression 40건만 존재 | Held-out 80건 + Challenge 24건 Human label freeze 후 측정 | `213-ai-moderation-heldout/README.md` | Prompt/Policy 변경·신규 Prompt v3 생성 없음; Challenge 수치는 전체 정확도로 합산 금지 |
| #142 Reservation Peak | 같은 식당·날짜로 조회가 몰리는 상황을 측정했고, Pool 30 확대는 미채택·CPU 확장은 효과 확인만 했다. | `MEASURED_AND_REJECTED` | `MEASURED` | Stress p95 13.14s/p99 19.62s, Pool 30 p95 19.21s로 악화, 8 vCPU p95 4.44s로 개선 | t3.small·Pool 10에서 CPU/Pool 동시 포화 | Pool 30 미채택, 인스턴스/Pool 운영 조정은 Human 후속 판단 | `142-reservation-peak/README.md` | 운영 인스턴스 크기 변경과 추가 Pool 튜닝은 이번 범위 밖 |
| #235 Restaurant View Hot-path | 회차 조회 반복 쿼리를 배치화해 주요 조회 hot-path 비용을 낮췄다. | `MERGED` | `MEASURED` | Load p95 802.66ms→60.27ms, Stress p95 13.14s→1.34s, RPS 51.4→195.3 | 회차별 반복 쿼리와 CPU/Pool 포화 | 배치 조회 구조로 개선 | `restaurant-view-hotpath/README.md` | 최고 Stress에서는 여전히 CPU/Hikari Pool 포화; 완전 해소 주장 금지 |
| #146 Refund K6 | 현재 환불 완료 구조는 측정한 Load 수준에서 유지하고, 완료 트랜잭션 지연이 커질 때의 Pool 위험을 한계로 남겼다. | `MERGED` | `MEASURED` | A/B/C/E p95 115ms 이하, D 지연 주입 1000ms에서 p99 13.9s·pending 92 | Spring Event 전환 후보 | 현재 동기 Port/Adapter 구조 유지 | `146-refund-completion-k6/README.md` | Load 1개 지점, row lock wait 직접 수집 없음; #259 동시성 결함은 별도 |
| #169 App HA / Blue-Green | ALB 뒤 Active App EC2 2대와 Blue-Green traffic switch/rollback을 App layer 기준으로 검증했다. | `MERGED` | `MEASURED` | 정상 배포 public 2,787/2,787 HTTP 200, 실패 0, 관측 downtime 0s; rollback p95 33ms/p99 40ms | 단일 EC2 중단 구간 측정(측정 위치 다름) | ALB + Active App EC2 2대 + Blue/Green weight 전환 | `169-app-ha/README.md` | RDS/Redis/Kafka까지 포함한 전체 시스템 HA 아님 |
| #170 Redis Pub/Sub Chat | 다중 Application Instance의 실시간 Chat 전달 경로를 Redis Pub/Sub으로 구현했다. | `MERGED` | `VERIFIED` | 인스턴스 간 전달, 인증 STOMP A↔B 전달, 방별 local STOMP 격리, ChatMessage 단일 저장 | 로컬 SimpleBroker만으로 원격 인스턴스 세션 전달 불가 | DB 저장 후 Redis Pub/Sub → 각 인스턴스 local STOMP 전달 | `170-chat-redis-pubsub/README.md` | Redis Pub/Sub은 메시지를 저장·재생하지 않음; DB cursor 조회가 복구 경로 |
| #191 Auto Scaling | 실제 부하에서 App EC2 Auto Scaling 필요성을 측정했으나 현재 조건에서는 도입하지 않았다. | `MEASURED_AND_REJECTED` | `MEASURED` | Pool 10 p95 35.4ms/p99 358.79ms·pending 40~60, Pool 12 재현 p95 22.42ms/p99 94.55ms·pending 거의 0 | App CPU 포화 근거 부족, Hikari 대기 선행 | Active App EC2 2대 유지, Hikari 12 검증, Inactive EC2 평상시 STOP | `191-auto-scaling/README.md` | Pool 10→12 단일 원인 개선 주장 금지; ASG/Scaling Policy 미도입 |
| #192 AI Worker Split | Kafka AI Consumer를 별도 Worker/MSA로 분리하지 않고 통합 모놀리스를 유지했다. | `MEASURED_AND_REJECTED` | `MEASURED` | AI 지연 100ms→3s에도 send service p95 12~18ms, Consumer 중단 15/15 복구·유실 0, 실패 5/5 DLT | Worker/MSA 분리 후보 | 같은 애플리케이션 안의 Kafka Consumer 유지 | `192-ai-worker-scaling/README.md` | Fake AI·경량 부하; 실제 HTTP/STOMP p95와 Provider 429는 운영 지표에서 재검토 |
| #198 Schema Migration | 최종 production은 `ddl-auto=validate`로 Entity mapping과 DB schema를 검증하고, additive schema 호환성을 확인했다. | `MERGED` | `VERIFIED` | production schema snapshot, empty DB reproducibility, Blue/Green same RDS read/write, nullable additive rollback compatibility PASS | prod `ddl-auto=update` | prod `ddl-auto=validate`, additive-first schema policy | `db-schema-migration/README.md` | destructive migration rollback safety와 cleanup 일부는 별도 확인 필요 |
| #256 Email Outbox Async | Email Outbox signal은 전용 bounded executor로 요청 스레드 밖에서 Processor를 호출한다. | `MERGED` | `VERIFIED` | dispatch <500ms 단위 검증, executor 제출 거부 시 PENDING 유지·scheduler 복구 | AfterCommit 경로가 같은 요청 스레드에서 Processor 호출 | `EmailOutboxSignalDispatcher` → `emailOutboxExecutor` → `EmailOutboxProcessor.signal` | `256-email-outbox-async/README.md` | AWS/K6/SMTP protocol 재측정 없음; 성능 개선 수치 주장 금지 |
| #274 Outbox+Async vs Kafka | Kafka는 속도 개선이 아니라 broker에 남은 대기 record·Consumer Group·Retry/DLT·독립 consumer 운영 경계를 위해 유지한다. | `OPERATIONAL_BOUNDARY_ADOPTED` | `MEASURED` | 30건 drain Async 5.394s, Kafka 7.210s; 같은 Outbox 조건 lost 0·duplicate 0 | Kafka 성능 우위와 Kafka 단독 유실 방지 가정 | 속도·독점 유실 방지 주장은 기각, broker 대기 record·Consumer Group·Consumer Lag·Retry/DLT·실패 영향 분리 경계 근거로 Kafka 유지 | `274-outbox-async-vs-kafka/README.md` | Retry/DLT 강제 실패 주입 효과는 이 비교 실험으로 검증하지 않음 |
| #277 Restaurant Feedback Insight | 같은 ChatMessageCreatedEvent를 독립 Consumer Group이 재사용해 OWNER용 익명 집계 Insight를 만든다. | `MERGED` | `VERIFIED` | Moderation/Insight 각자 소비, Insight 실패가 Moderation에 영향 없음, 전용 Retry/DLT, 보존된 event 원본 소비 59건 | 신규 이벤트/API 추가 후보 | 기존 Kafka topic/schema 유지, 독립 group으로 같은 Event 재사용 | `277-restaurant-feedback-event-reuse/README.md` | production 기본 disabled; 보존된 event 원본 소비는 Kafka 레벨 검증이며 실제 운영 enable/Provider 품질 아님 |

## 최종 발표 문구 변환 규칙

### 좋은 예

```text
문제
→ Before 핵심 지표·현상
→ 변경
→ After 핵심 지표·현상
→ 남은 한계
```

### 금지 예

```text
Kafka 도입 자체로 처리량 문제가 해결됐다고 주장했다.
```

실제 처리량 측정이 없다면 이런 표현을 사용하지 않는다.

### 허용 예

```text
Kafka 장애 중에도 ChatMessage와 Outbox가 보존되고,
복구 후 적체 이벤트가 다시 처리되는 것을 N건 반복 실험으로 확인했다.
동일 조건의 Consumer Lag·처리량은 Evidence에 기록했다.
```

위 문구도 실제 N건 반복 실험을 수행한 경우에만 사용한다.
