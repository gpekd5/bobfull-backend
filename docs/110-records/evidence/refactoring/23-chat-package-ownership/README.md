# Issue #23 Chat 패키지 및 기술 구현 소유권 정리 Evidence

## 검증 대상

기존 Chat 계층형 package와 top-level Kafka/Outbox에 흩어진 Chat 전용 구현을 현재 책임에 따라
`presentation/application/domain/infrastructure`로 재배치한다. HTTP/STOMP 계약, JPA mapping, Moderation,
Transaction/after-commit, Kafka/Outbox, Redis/WebSocket과 Scheduler 동작은 바꾸지 않고 위치, package
declaration, import와 test reference만 정리한다.

## 유지할 기존 동작

- ChatRoom/ChatMessage HTTP API path, request, response와 status
- STOMP/WebSocket endpoint, application/subscription destination, payload와 status
- Chat Entity의 table, column, index와 relation mapping
- 메시지 저장/조회, Moderation 판정과 실패 처리
- Transaction propagation과 DB/Outbox transaction 이후 Redis·Moderation after-commit 순서
- Kafka topic, consumer group, concurrency, retry, DLT와 오류 처리 계약
- Outbox claim/retry/완료·실패 처리와 Scheduler 주기
- Redis channel, 재구독, WebSocket 전달과 AI Provider 선택 동작

## 기준 코드

- Before SHA: `f24d41c07f2509837c36203737287179540754a1`
- Package 이동 구현 SHA: `037c201097c55e6de317cc419707373bf06cc599`
- After source SHA: `17518ec9935d2452739ebcaa58df10aaac273128`
- 기준 브랜치: 최신 `develop`에서 생성한 `refactor/23-chat-packages`

## 환경·실행 조건

- 측정일: 2026-09-14 KST
- OS: Windows 11 amd64
- Gradle Wrapper: 9.5.1
- Java toolchain: 17
- SonarQube: `26.9.0.129388-community`
- SonarQube project key: `bobfull-backend`

## 측정 방법

### 파일·패키지 구조

```powershell
git ls-tree -r --name-only <Before SHA> -- src/main/java/com/bobfull/chat
git diff --name-status -M <Before SHA>..<After SHA> -- src/main/java src/test/java
Get-ChildItem src/main/java/com/bobfull/chat -Recurse -Filter *.java
Get-ChildItem src/test/java/com/bobfull/chat -Recurse -Filter *.java
rg -n 'com\.bobfull\.chat\.(adapter|config|controller|dto|entity|port|realtime|repository|security|service)(\.|;)' src/main/java src/test/java --glob '*.java'
rg -n '(Chat|chat)' src/main/java/com/bobfull/kafka src/main/java/com/bobfull/outbox
```

Before의 Chat root와 이동 대상 top-level Kafka/Outbox 파일을 합산하고, After는 네 책임 package와
infrastructure 기술 하위 package로 실제 경로를 집계했다. package declaration은 production/test Java의
실제 디렉터리 경로와 비교했고, 기존 FQCN과 top-level Chat 전용 기술 구현은 source 전체를 검색했다.

### 동작 보존 Guardrail

```powershell
rg -n '@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|MessageMapping)'
rg -n '@Table|@Transactional|Propagation\.REQUIRES_NEW|AfterCommitExecutor\.run'
rg -n '@KafkaListener|@Scheduled|DeadLetterPublishingRecoverer|DefaultErrorHandler'
rg -n 'enableSimpleBroker|setApplicationDestinationPrefixes|addEndpoint|ChannelTopic'
git diff --find-renames=50% --unified=0 <Before SHA>..<After SHA> -- '*.java'
git diff --check <Before SHA>..<After SHA>
```

package/import/공백과 import 정렬만 정규화한 뒤 모든 이동 Java의 동작 본문을 비교했다. HTTP/STOMP,
JPA, Transaction/after-commit, Kafka/Outbox, Redis/WebSocket, Scheduler와 AI Moderation 관련 annotation,
상수, property key, method body 변경 line은 0이었다. 이 항목들은 개선 목표가 아니라 기존 동작 보존
Guardrail이다.

### 테스트·build

```powershell
.\gradlew.bat test --tests "com.bobfull.chat.*" --rerun-tasks
.\gradlew.bat test --tests "com.bobfull.kafka.consumer.RestaurantInsight*" --tests "com.bobfull.reservation.application.service.ReservationConfirmationServiceTest" --rerun-tasks
.\gradlew.bat compileJava compileTestJava
.\gradlew.bat clean build
```

Gradle 결과와 `build/test-results/test/TEST-*.xml`의 suites/tests/failures/errors/skipped 합계를 함께
확인했다. Sonar 회귀 import 정리 뒤 최신 source HEAD에서 전체 `clean build`를 다시 실행했다.

### SonarQube

```powershell
$revision = git rev-parse HEAD
.\gradlew.bat clean classes testClasses sonar "-Dsonar.host.url=$env:SONAR_HOST_URL" "-Dsonar.token=$env:SONAR_TOKEN" "-Dsonar.scm.revision=$revision"
```

Before/After 모두 같은 로컬 서버, project key, scanner, Quality Profile과 Quality Gate로 분석했다.
Compute Engine 완료와 issue persistence는 같은 SonarQube container log에서 task id와 `SUCCESS`를
확인했고, 열린 Issue는 인증된 `api/issues/search` 결과를 path/rule/type/key로 비교했다. Token 값은
출력하거나 기록하지 않았다.

## Before 결과

### 구조

| 기존 Chat package | Production Java |
|---|---:|
| `adapter` | 6 |
| `config` | 2 |
| `controller` | 4 |
| `dto` | 11 |
| `entity` | 11 |
| `port` | 3 |
| `realtime` | 4 |
| `repository` | 4 |
| `security` | 4 |
| `service` | 12 |
| Chat root 소계 | 61 |
| top-level Chat 전용 Kafka | 6 |
| top-level Chat 전용 Outbox | 5 |
| 합계 | 72 |

- Chat root test source: 43 files
- top-level Chat 전용 Kafka/Outbox test source: 5/4 files
- 전체 이동 대상 test source: 52 files
- 현재 책임 기준 목표 재분류: presentation 11, application 18, domain 11, infrastructure 32

### SonarQube

- 분석 SHA: `f24d41c07f2509837c36203737287179540754a1`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`7355bcc3-f959-42c9-9fc0-b5d0401503f8`)
- analysis id: `2301f764-4335-47a2-97ac-bfc8f76a5387`
- issue persistence: inserts 0, updates 2
- 프로젝트 열린 Issue: 326 (BUG 14, CODE_SMELL 311, VULNERABILITY 1)
- Chat 열린 Issue: 122 (BUG 6, CODE_SMELL 116, Vulnerability 0)
- Chat `java:S1128`: 5

## 변경 내용

- Controller와 외부 DTO 11개를 `presentation`으로 이동
- Service, 기존 Port와 내부 command/event 18개를 `application`으로 이동
- Entity와 domain enum 11개를 `domain`으로 이동
- Repository/기존 Adapter/AI/Kafka/Outbox/Redis/WebSocket 32개를 `infrastructure`로 이동
- Chat 전용 top-level Kafka 6개와 Outbox 5개의 소유권을 Chat infrastructure로 이동
- 관련 test source 52개와 외부 도메인의 import/test reference 갱신
- 초기 After Sonar에서 새로 탐지된 동일 package 중복 import 11개만 제거
- 새 Facade/Port/Adapter, 외부 계약 또는 비즈니스 로직 변경 없음

## After 결과

### 구조

| 책임 | Production | Test source |
|---|---:|---:|
| `presentation` | 11 | 2 |
| `application` | 18 | 9 |
| `domain` | 11 | 0 |
| `infrastructure` | 32 | 41 |
| 합계 | 72 | 52 |

Infrastructure production 상세는 adapter 2, ai 5, kafka 6, outbox 5, redis 5, repository 4,
websocket 5다.

- 기존 Chat 최상위 package/FQCN 잔존: 0
- top-level Chat 전용 Kafka/Outbox production 잔존: 0/0
- package declaration/path 불일치: 0
- HTTP mapping annotation: 6
- STOMP `@MessageMapping`: 1
- JPA Entity/Table: 4/4
- Transaction annotation/`REQUIRES_NEW`: 5/1
- `AfterCommitExecutor.run`: 3
- Kafka listener: 1
- Outbox Scheduler: 2
- 정규화한 Java 동작 본문 Before/After 차이: 0
- Java Diff: 151 files, 446 insertions, 451 deletions; whitespace 오류 0

## build/test/Sonar 회귀 검증

| 검증 | 결과 | 증거·한계 |
|---|---|---|
| `compileJava` | PASS | 최신 source 전체 compile 성공 |
| `compileTestJava` | PASS | 최신 test source 전체 compile 성공 |
| Chat 관련 테스트 | PASS | 47 suites/161 cases, failures/errors 0, 환경 조건 skip 14 |
| 외부 영향 테스트 | PASS | RestaurantInsight Kafka와 Reservation 5 suites/16 cases, 실패·skip 0 |
| 전체 `clean build` | PASS | 최신 source HEAD에서 root 944 tests, failures/errors 0, skipped 64; Lambda 11 tests 통과 |
| 기존 package/FQCN 잔존 | PASS | 기존 10개 최상위 package와 source FQCN 검색 0건 |
| 기술 구현 소유권 | PASS | top-level Chat 전용 Kafka/Outbox production 잔존 0/0 |
| package/path 정합성 | PASS | Chat production/test Java 불일치 0건 |
| 동작 보존 | PASS | Guardrail annotation·상수·property·method body 변경 line 0 |
| Sonar 분석 실행 | PASS | Before/초기 After/수정 After Gradle 및 Compute Engine 모두 SUCCESS |
| Sonar 신규 회귀 판정 | PASS | 이동으로 생긴 S1128 11건 정리 후 Before와 열린 Issue 수·분포 동일, 신규 열린 Issue 0 |

### Guardrail Before/After

| Guardrail | Before | After |
|---|---|---|
| HTTP API mapping | 6 | 6 |
| STOMP mapping / endpoint | 1 / `/ws` | 1 / `/ws` |
| broker / application prefix | `/sub` / `/pub` | `/sub` / `/pub` |
| JPA Entity / Table | 4 / 4 | 4 / 4 |
| Transaction / `REQUIRES_NEW` | 5 / 1 | 5 / 1 |
| after-commit dispatch | 3 | 3 |
| Kafka listener | 1 | 1 |
| Kafka topic / group 기본값 | `bobfull.chat.message-created.v1` / `bobfull-chat-moderation` | 동일 |
| Kafka DLT 기본값 | `bobfull.chat.message-created.dlt.v1` | 동일 |
| Outbox Scheduler | 2 | 2 |
| Redis channel 기본값 | `bobfull:chat:messages` | 동일 |
| Redis WebSocket destination | `/sub/chat/rooms/{id}` | 동일 |
| AI Moderation 동작 본문 | 기준 본문 | 변경 0 line |

### SonarQube 초기 After

- 분석 SHA: `037c201097c55e6de317cc419707373bf06cc599`
- Compute Engine: `SUCCESS` (`c6fbcd0b-ba7a-4705-a376-99b15e16a55c`)
- analysis id: `1bcdc87c-d443-4f83-bcb3-2608d9d0f9ea`
- issue persistence: inserts 32, updates 122
- 프로젝트 열린 Issue: 337, Chat 열린 Issue: 133
- 32개 새 key 중 21개는 기존 이슈가 이동 경로에서 재등록된 건이다. 사라진 기존 key와 새 key의
  file/rule/type/message 분포가 각각 S1128 2, S1659 2, S2681 1, S2699 1, S5778 7, S8924 8로 일치했다.
- 나머지 11개는 Kafka/Redis/WebSocket 구현이 같은 package로 합쳐지며 생긴 `java:S1128` 동일 package
  import였다. Bug/Vulnerability 수는 증가하지 않았지만 package 이동이 만든 신규 Code Smell이므로 제거했다.

### SonarQube 수정 After

- 분석 SHA: `17518ec9935d2452739ebcaa58df10aaac273128`
- Gradle 분석: `BUILD SUCCESSFUL`
- Compute Engine: `SUCCESS` (`ee24f8a7-8d6a-4ff2-a9e2-de42f7ee89c2`)
- analysis id: `b2ded382-1c94-4932-8cb4-0bf0a71f8b44`
- issue persistence: inserts 0, updates 22
- 프로젝트 열린 Issue: 326 (BUG 14, CODE_SMELL 311, VULNERABILITY 1)
- Chat 열린 Issue: 122 (BUG 6, CODE_SMELL 116, Vulnerability 0)
- Chat rule/type 분포는 Before와 동일하고 신규 열린 Issue는 0이다.
- 21개 기존 이슈의 key 재등록은 파일 이동 추적 과정에서 발생했으며 같은 rule/파일명/message 분포로
  대응된다. import 제거로 일부 line만 이동했으며 새로운 결함으로 판정하지 않았다.
- 현재 전체 Quality Gate: `ERROR` (기존 전체 Issue를 이번 리팩토링에서 일괄 수정하지 않음)

## 결과 해석

Chat production/test 파일은 Before/After 모두 72/52개다. 목표 재분류 11/18/11/32가 실제 tree와
일치하고, 기존 package/FQCN과 top-level Chat 전용 Kafka/Outbox 잔존은 모두 0이다. Guardrail 본문 차이
0, 관련 테스트와 최신 source 전체 build 통과로 위치와 소유권만 정리하면서 기존 동작을 보존했다.

Sonar 초기 After의 순증 11건은 package 합병으로 불필요해진 동일 package import였고 해당 import만
제거했다. 최종 열린 Issue 수와 rule/type 분포는 Before와 동일하므로 이번 이동으로 남은 신규
Bug/Vulnerability/Code Smell 회귀는 없다. 기존 122건과 전체 Quality Gate `ERROR`는 Baseline으로 남긴다.

## 검증 한계

- Chat 관련 테스트 14건과 전체 build 64건은 실제 OpenAI 등 선택적 외부 환경 조건 때문에 skip됐다.
  환경 변수나 외부 계정을 임의로 만들지 않았다.
- 실제 AWS 배포, 운영 Kafka/Redis, 외부 AI Provider와 브라우저 WebSocket E2E는 수행하지 않았다.
- API/DB 운영 환경 smoke test는 수행하지 않았고 annotation·상수 동일성, 관련 테스트와 전체 build로
  동작 보존을 확인했다.
- SonarQube는 단일 project의 main branch 분석을 Before/After 순서로 수행했다. 파일 이동 시 일부 기존
  issue key가 바뀌므로 key 자체가 아니라 rule/type/message와 파일 대응 관계를 함께 비교했다.
- 이 리팩토링은 기존 Sonar Issue 감소, 외부 의존 제거, 성능 또는 신뢰성 개선 효과를 주장하지 않는다.

## 관련

- [Issue #23](https://github.com/gpekd5/bobfull-backend/issues/23)
- [Master Issue #18](https://github.com/gpekd5/bobfull-backend/issues/18)
- [Issue #10 SonarQube Baseline](../../v3/10-sonarqube-baseline/README.md)
