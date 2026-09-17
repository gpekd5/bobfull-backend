# Issue #28 1차 리팩토링 후 기존 기능 동작 보존 검증 Evidence

## 검증 대상

- [Issue #28](https://github.com/gpekd5/bobfull-backend/issues/28)
- Issue #18, #15, #16까지 반영된 최신 `develop`의 compile, build와 전체 테스트
- 이전 package/import 잔존과 Spring scan/bean 연결
- HTTP, STOMP, Security, Transaction, Lock, Kafka, Outbox, Scheduler 계약
- 로그인부터 예약, 결제, 채팅, 취소와 환불까지의 Core Smoke 흐름

이 문서는 기능이나 구조를 변경한 결과가 아니라, 1차 리팩토링이 합쳐진 기준 코드에서 기존 동작이
유지되는지 검증한 결과를 기록한다.

## 유지할 기존 동작

- HTTP/STOMP path, request, response, status와 권한
- DB schema와 JPA Entity mapping
- 로그인, 식당 검색, 예약 준비, 결제, 예약 확정, 채팅, 취소와 환불 흐름
- Transaction boundary, `MANDATORY`, `REQUIRES_NEW`와 Lock 순서
- Kafka topic, consumer group, retry와 DLT
- Outbox enqueue, after-commit, claim, retry와 상태 전이
- Scheduler 설정과 실패 격리·멱등성

## 기준 코드

| 구분 | SHA | 설명 |
|---|---|---|
| 검증 기준 | `69a58b8cfb4d58c0b8ed8e00a3897ae5dc610cd8` | Issue #18, #15, #16이 반영된 최신 `develop` |
| `origin/develop` | `69a58b8cfb4d58c0b8ed8e00a3897ae5dc610cd8` | 검증 시작 시 원격 기준과 동일 |
| Before/After 비교 | `NOT_APPLICABLE` | 구현 변경 없이 동일 SHA의 동작 보존을 검증하는 Issue |

- 검증 branch: `test/28-regression-verification`
- 각 단계 시작과 종료 시 working tree가 clean임을 확인했다.
- Production/Test 코드와 설정을 변경하지 않았다.

## 환경·실행 조건

- Windows PowerShell
- Java 17, repository Gradle Wrapper
- Docker Desktop과 Docker daemon
- 전체 build의 Kafka/Redis 통합 검증은 기존 Testcontainers 테스트를 사용했다.
- MySQL 동시성 검증과 Core Smoke는 기존 사용자 DB/컨테이너와 분리한 disposable container를 사용했다.
- Core Smoke profile: `local,performance`
- 결제/환불: `PerformanceTestPaymentAdapter`, `PerformanceTestRefundAdapter`
- 채팅 AI: `FakeAiModerationAdapter`
- 실제 secret은 사용·출력·기록하지 않았다.
- 실제 PortOne, S3, SMTP와 OpenAI는 호출하지 않았다.

## 측정 방법

### 1단계: Compile과 정적 회귀

- Gradle Wrapper로 `compileJava`, `compileTestJava`를 실행했다.
- Issue #18에서 확정한 package 구조와 현재 production/test import를 비교해 이전 경로 잔존을 검색했다.
- 기존 Application Context와 선별 테스트로 Component, Repository, Entity scan과 주요 Bean 등록을 확인했다.
- Issue #18 기준 HTTP mapping 목록과 현재 mapping의 고유 항목을 비교했다.
- STOMP prefix, Security 권한, Transaction propagation, Lock 순서, Kafka/Outbox/Scheduler 설정을 코드와
  기존 테스트에서 확인했다.

### 2단계: 전체 build와 인프라 통합

```powershell
.\gradlew.bat clean build
```

- clean build XML에서 root와 Lambda 테스트 결과를 집계했다.
- Testcontainers 테스트가 사용한 실제 Kafka/Redis container와 검증 범위를 클래스별로 확인했다.
- 환경변수로 활성화되는 기존 MySQL 동시성 테스트는 별도의 disposable `mysql:8.4`에서 실행했다.
- Outbox와 Scheduler는 기존 단위·통합 테스트에서 상태 전이와 실패 격리를 구분해 확인했다.

### 3단계: Core Smoke

- disposable MySQL, Redis와 Kafka를 별도 포트로 실행했다.
- `local,performance` profile로 애플리케이션을 기동하고 Actuator health `UP`을 확인했다.
- API로 OWNER/MEMBER와 식당, SharedTable, 미래 TimeSlot을 생성했다.
- HTTP와 STOMP로 예약 준비부터 결제, 채팅, 취소와 환불까지 순서대로 실행했다.
- API로 노출되지 않는 최종 상태와 Outbox 상태만 disposable MySQL에서 읽기 전용으로 교차 확인했다.
- 종료 후 애플리케이션과 이번 검증에서 만든 container만 제거하고 기존 사용자 container와 volume을
  유지했다.

## 1단계 결과: Compile과 정적 회귀

| 항목 | 결과 | 확인 내용 |
|---|---|---|
| `compileJava` | PASS | Production Java compile 성공 |
| `compileTestJava` | PASS | Test Java compile 성공 |
| 이전 package/import | PASS | 의미 있는 이전 경로 잔존 0 |
| Application Context | PASS | Context 기동 |
| Component Scan | PASS | 주요 Component Bean 등록 |
| Repository Scan | PASS | JPA Repository 연결 |
| Entity Scan | PASS | Entity mapping 등록 |
| 선별 테스트 | PASS | 27건 PASS |
| HTTP mapping | PASS | Issue #18 기준 고유 80개 대비 차이 0 |
| STOMP | PASS | `/ws`, `/pub`, `/sub` 유지 |
| Security | PASS | 주요 인증·권한 계약 유지 |
| Transaction | PASS | 경계 유지, `MANDATORY` 3건과 `REQUIRES_NEW` 14건 유지 |
| Lock | PASS | Reservation/Payment/TimeSlot Lock 순서 관련 지점 유지 |
| Kafka | PASS | topic, group, retry와 DLT 설정 유지 |
| Outbox | PASS | enqueue, claim, retry와 상태 구성 유지 |
| Scheduler | PASS | 활성 조건과 주기 설정 유지 |

정적 비교와 선별 테스트에서 실제 회귀 후보는 발견되지 않았다.

## 2단계 결과: 전체 build와 통합 테스트

### 전체 결과

| 항목 | 실행 | PASS | FAIL | SKIP | 결과 |
|---|---:|---:|---:|---:|---|
| Root clean build 테스트 | 945 | 881 | 0 | 64 | PASS |
| Lambda 테스트 | 11 | 11 | 0 | 0 | PASS |
| MySQL 전용 추가 실행 | 12 | 12 | 0 | 0 | PASS |
| 합계 실행 횟수 | 968 | 904 | 0 | 64 | PASS |

Root clean build에서 환경 조건 때문에 SKIP된 MySQL 동시성 테스트 중 대상 12건은 disposable MySQL에서
별도로 실행해 모두 PASS를 확인했다. `SKIP 64`는 FAIL과 구분하며, 환경·성능·수동 Evidence 또는 외부
연동 조건이 필요한 기존 테스트를 포함한다.

### Docker/Testcontainers

| 테스트 | 인프라 | 결과 | 실제 검증 범위 |
|---|---|---|---|
| `RedisChatCrossInstanceIntegrationTest` | Redis | PASS | 다중 App instance Pub/Sub과 채팅방 격리 |
| `ChatMessageOutboxProcessorIntegrationTest` | Kafka | PASS | publish, 실패 후 retry, `COMPLETED` |
| `ChatModerationConsumerIntegrationTest` | Kafka | PASS | consume, retry, DLT, 중복 멱등성, lag metric |
| `ChatModerationDltPublishFailureIntegrationTest` | Kafka | PASS | DLT 발행 실패 시 최종 실패 처리 보호 |
| `RestaurantInsightKafkaIntegrationTest` | Kafka | PASS | 독립 consumer group, 멱등성, retry와 DLT |
| `RestaurantInsightDltPublishFailureIntegrationTest` | Kafka | PASS | DLT 실패 시 최종 실패 metric 보호 |

### MySQL 동시성과 Lock

| 항목 | 결과 | 검증 내용 |
|---|---|---|
| Payment 완료/만료 경쟁 | PASS | 선점 순서, 완료 전 재검증, 동시 JOIN 결제 |
| 취소/환불 경쟁 | PASS | 완료 경쟁, gap lock과 예약 인원 재계산 |
| 예약 준비/참여 | PASS | 중복 READY, 마지막 좌석과 중복 참여 경쟁 |
| `NO_SHOW` | PASS | 동시 상태 변경 정합성 |
| SharedTable | PASS | 동시 변경의 Lock 기반 정합성 |

### Kafka, Outbox와 Scheduler

| 영역 | 결과 | 확인 내용 |
|---|---|---|
| Kafka producer/consumer | PASS | 실제 broker publish/consume |
| Kafka retry/DLT | PASS | 일시 실패 복구, 반복 실패 DLT와 DLT 실패 보호 |
| Consumer group/listener | PASS | 독립 group과 listener 연결 |
| Outbox enqueue/claim | PASS | enqueue와 동시 claim 단일 선점 |
| Outbox 상태 | PASS | `PENDING -> PROCESSING -> COMPLETED/FAILED` |
| Outbox retry/stale recovery | PASS | backoff, 수동 retry와 stale `PROCESSING` 회수 |
| Outbox after-commit | PASS | commit 전 미처리, commit 후 신호 |
| Scheduler Bean 조건 | PASS | property별 생성/비생성 |
| Scheduler 실패 격리 | PASS | 한 후보 실패 후 다음 후보 처리 |
| 실제 시간 기반 주기 실행 | NOT_RUN | 수동 대기 실행은 검증 범위에서 제외 |

전체 clean build와 추가 MySQL 테스트에서 실패는 없었고 실제 회귀 후보도 발견되지 않았다.

## 3단계 결과: Core Smoke

### 기동과 연결

| 항목 | 결과 | 근거 |
|---|---|---|
| Spring Context | PASS | 애플리케이션 정상 시작 |
| MySQL | PASS | 상태 저장·조회 성공 |
| Redis | PASS | Chat publish/subscribe 로그 확인 |
| Kafka | PASS | partition 할당, Outbox publish와 AI consumer 처리 |
| HTTP | PASS | Actuator health `UP`, Core API 응답 성공 |
| STOMP | PASS | CONNECT, SUBSCRIBE, SEND와 MESSAGE 수신 |

### 사용자 흐름

| 순서 | 기능 | 결과 | 확인 내용 |
|---:|---|---|---|
| 1 | OWNER/MEMBER 준비 | PASS | 계정과 Restaurant/SharedTable/TimeSlot 생성 |
| 2 | MEMBER login | PASS | JWT 발급과 인증 API 사용 |
| 3 | Restaurant Search | PASS | 생성한 Restaurant 조회 |
| 4 | TimeSlot | PASS | `partySize=2`로 미래 Session 조회 |
| 5 | Reservation Prepare | PASS | `READY` Payment 생성 |
| 6 | Payment Complete | PASS | performance Adapter로 `PAID` 전이 |
| 7 | Reservation Confirmed | PASS | Reservation `CONFIRMED`, Participant `RESERVED` |
| 8 | ChatRoom | PASS | 확정 예약의 ChatRoom 조회 |
| 9 | STOMP Message | PASS | 송수신과 DB 메시지 저장 |
| 10 | Reservation Cancel | PASS | 취소 접수 후 Reservation/Participant 취소 완료 |
| 11 | Refund | PASS | performance Adapter로 Refund `COMPLETED` |

### 상태 전이

| 대상 | 확인한 전이 | 결과 |
|---|---|---|
| Payment | `READY -> PAID -> REFUNDED` | PASS |
| Reservation | 결제 전 미생성 `-> CONFIRMED -> CANCELLED` | PASS |
| Participant | `RESERVED -> CANCELLED` | PASS |
| Refund | `REQUESTED -> COMPLETED` | PASS |
| ChatRoom Outbox | `CHAT_ROOM_CREATION_REQUESTED -> COMPLETED` | PASS |
| ChatMessage Outbox | `CHAT_MESSAGE_CREATED -> COMPLETED` | PASS |
| Email Outbox | `EMAIL_RESERVATION_CREATED -> PENDING/retry` | SMTP 부재로 전달 미완료 |

Kafka로 소비된 Chat moderation 결과의 provider가 `Fake`, 결과가 `SAFE`임을 DB에서 확인했다. 실제 OpenAI
호출은 발생하지 않았다. SMTP 연결 실패로 Email Outbox가 재시도 상태였지만 결제, 예약, 채팅, 취소와
환불 API 흐름에는 영향을 주지 않았다.

## NOT_RUN

| 항목 | 이유 | 확인한 대체 범위 |
|---|---|---|
| 실제 PortOne 결제/환불 | 외부 자격 증명을 사용하지 않는 회귀 검증 | performance Adapter를 통한 내부 상태 전이 |
| 실제 S3 | 이미지 업로드가 Core Smoke 필수 흐름이 아님 | 대체 실행 없음 |
| 실제 SMTP 전달 | 외부 자격 증명과 서버를 사용하지 않음 | Email Outbox enqueue와 retry 상태 확인 |
| 실제 OpenAI | 개인정보와 외부 자격 증명을 사용하지 않음 | Fake Adapter의 Kafka consume과 결과 저장 |
| Scheduler 실제 시간 경과 | 수동 대기 실행 제외 | Bean 조건, 호출, retry/recovery/idempotency 테스트 |
| `kafka-evidence` 전용 실행 | 별도 수동 Evidence 조건과 로컬 broker 환경 필요 | 기본 Testcontainers에서 publish/consume/retry/DLT 확인 |

## Warning과 검증 한계

- compile 과정에서 기존 deprecated API와 unchecked operation 경고가 있었지만 build 실패는 없었다.
- MySQL 테스트 종료와 빈 Smoke schema 생성 시 `create-drop`/DDL이 존재하지 않는 FK를 제거하려는 경고가
  있었지만 테스트와 애플리케이션 기동은 성공했고 disposable DB는 제거됐다.
- Windows Netty 환경에서 일부 TCP keepalive option 경고가 있었지만 Redis/Kafka 연결과 메시지 처리는
  성공했다.
- clean build의 SKIP 64건을 PASS로 간주하지 않는다. 별도 실행한 MySQL 12건 외의 환경·성능·수동·외부
  조건 테스트는 실행하지 않은 상태로 남는다.
- 실제 외부 서비스 계약과 운영 환경을 검증하지 않았으므로 운영 환경 전체의 정상 동작을 주장하지 않는다.
- SonarQube는 선택 검증이며 이번 Issue 실행 결과에 포함하지 않았다.

## 결과 해석

- compile과 전체 clean build가 PASS했고 전체 실행에서 FAIL은 0건이었다.
- 이전 package/import 잔존과 Spring scan/bean 누락은 발견되지 않았다.
- HTTP/STOMP, Security, Transaction, Lock, Kafka, Outbox와 Scheduler의 주요 계약이 유지됐다.
- Kafka/Redis/MySQL 통합 테스트, MySQL 동시성 테스트와 Core Smoke가 PASS했다.
- 실제 외부 연동은 `NOT_RUN`이며 performance/Fake Adapter로 내부 계약과 상태 전이만 검증했다.

따라서 **현재 검증 범위에서는 1차 리팩토링으로 인한 기존 기능 회귀가 발견되지 않았다.**

## 관련

- Issue: [#28](https://github.com/gpekd5/bobfull-backend/issues/28)
- Package 구조 정리: [#18](https://github.com/gpekd5/bobfull-backend/issues/18)
- 코드 작성 기준 적용: [#15](https://github.com/gpekd5/bobfull-backend/issues/15)
- 주석 정리: [#16](https://github.com/gpekd5/bobfull-backend/issues/16)
- 후속 구조 리팩토링: [#20](https://github.com/gpekd5/bobfull-backend/issues/20)
- PR: [#43](https://github.com/gpekd5/bobfull-backend/pull/43)
