# BobFull 프로젝트 구조

## 1. 목적과 기준

이 문서는 처음 저장소를 보는 개발자가 코드와 운영 자산의 소유 위치를 빠르게 찾기 위한 구조 안내서다.
Repository Root는 Issue #37, Java package는 Issue #38 완료 구조를 기준으로 한다.

- 기능별 내부 구조를 같은 모양으로 강제하지 않는다.
- package 이름은 현재 파일의 실제 책임과 소유 기능을 드러내야 한다.
- API, 데이터 모델, 정책과 트랜잭션의 상세 계약은 각 기준 문서를 따른다.
- 네이밍과 Annotation 기준은 후속 Issue #15에서 `code-convention.md`와 함께 정리한다.

## 2. Repository Root

```text
bobfull-backend/
├─ .github/                 Issue/PR template과 CI/CD workflow
├─ docs/                    제품, API, 데이터, 아키텍처, 개발 및 검증 문서
├─ gradle/                  Gradle Wrapper 설정
├─ lambda/                  독립 배포하는 식당 이미지 검증 Lambda
├─ ops/                     배포, 모니터링, 부하 테스트, 운영 도구
├─ src/                     Backend application source와 test
├─ AGENTS.md                BobFull AI 작업 진입점
├─ CLAUDE.md                Claude Code 호환 진입점
├─ build.gradle             Backend 및 공통 Gradle build 설정
├─ settings.gradle          Backend와 Lambda module 구성
├─ docker-compose.yml       로컬 MySQL, Redis, Kafka, application 구성
└─ docker-compose.sonar.yml 로컬 SonarQube 구성
```

| 경로 | 담당 역할 | 대표 파일 | 포함하지 않는 책임 |
|---|---|---|---|
| `src/main` | Backend production code와 resource | `BobfullBackendApplication`, `application.yml` | Lambda production code |
| `src/test` | Backend 단위·통합·계약 테스트 | 기능별 `*Test` | 운영 부하 시나리오 |
| `lambda/restaurant-image-validator` | S3 식당 이미지 검증·승격 Lambda | `RestaurantImageValidationHandler` | Backend의 Presigned URL API |
| `ops` | 저장소와 함께 관리하는 운영 실행 자산 | 배포 script, Compose, k6 | 제품·API 계약 문서 |
| `.github` | GitHub 작업 진입점과 자동화 | `ci-backend-v1.yml`, PR template | 애플리케이션 런타임 설정 |
| `docs` | 기준 문서, 작업 가이드와 Evidence | `project-context.md`, `architecture.md` | 실행 가능한 production code |

## 3. Java Top-Level Package

Production Java 기준 진입점은 `src/main/java/com/bobfull`이며 Issue #38 완료 시점에 401개 파일이 있다.

| Package | 파일 수 | 담당 기능 | 대표 클래스 | 구조상 예외와 경계 |
|---|---:|---|---|---|
| `admin` | 55 | ADMIN/OWNER 운영 조회와 moderation 검토 | `AdminStatisticsController`, `AdminStatisticsQueryService` | 독자 Entity가 없어 `domain`을 만들지 않는다. |
| `auth` | 19 | 로그인, JWT, Refresh Token, Security | `AuthController`, `JwtTokenProvider`, `SecurityConfig` | 회원 Entity와 Role은 `member`가 소유한다. |
| `chat` | 74 | 채팅방·메시지·신고·AI moderation | `ChatMessageCommandService`, `ChatModerationConsumer` | Kafka, Outbox, Redis, WebSocket 구현을 자체 infrastructure가 소유한다. |
| `common` | 18 | 여러 기능이 공유하는 최소 기반 | `ApiResponse`, `BaseTimeEntity`, `AfterCommitExecutor` | 기능별 Processor나 전달 정책을 소유하지 않는다. |
| `member` | 9 | 회원 정보와 역할 | `Member`, `MemberService` | 인증 token과 filter는 `auth`가 소유한다. |
| `notification` | 10 | Email Outbox와 SMTP 전달 | `EmailOutboxProcessor`, `SmtpReservationNotificationAdapter` | 현재 공개 API나 독자 domain model이 없어 infrastructure만 둔다. |
| `payment` | 56 | Payment/Refund와 Settlement 조회 | `PaymentService`, `RefundCompletionService`, `SettlementController` | Settlement는 별도 Entity가 아니라 조회 모델이다. |
| `reservation` | 74 | 예약·참여·취소·노쇼·모집 마감 | `Reservation`, `ReservationPreparationService` | Payment/Restaurant 협력 계약은 현재 구조를 유지한다. |
| `restaurant` | 60 | 공급 영역의 식당·테이블·회차·이미지 | `RestaurantService`, `SharedTableService`, `TimeSlotService` | 하위 기능별로 필요한 계층만 둔다. |
| `restaurantinsight` | 25 | 식당 피드백 AI 분석과 OWNER 조회 | `RestaurantFeedbackInsightService`, `RestaurantFeedbackInsightConsumer` | Chat event를 소비하되 독립 Consumer Group을 가진다. |

`com.bobfull` 직속 클래스는 application bootstrap인 `BobfullBackendApplication`뿐이다.

## 4. 역할 Package 기준

| 경로 이름 | 담당 역할 | 들어가는 코드 | 포함하지 않는 책임 |
|---|---|---|---|
| `presentation` | HTTP/WebSocket 외부 진입점과 표현 | Controller, Request/Response DTO, presentation exception handler | 상태 규칙, DB 접근 구현 |
| `application` | 유스케이스 조합과 기능 경계 | Service, Command/Result/Model, Port, application event/exception | HTTP mapping, 외부 기술 구현 |
| `domain` | 핵심 상태와 순수 규칙 | Entity, 상태 Enum, Policy, domain ErrorCode | Controller, Repository 구현 |
| `infrastructure` | 저장소와 외부 기술 구현 | Repository, Adapter, Scheduler, Kafka, Redis, Outbox, AI provider | API 표현과 핵심 정책 재결정 |
| `common` | 여러 기능이 실제 공유하는 기반 | 공통 응답, 예외, auditing, monitoring, transaction/outbox 기반 | 한 기능에만 필요한 처리기와 정책 |

기능에 해당 책임이 없으면 빈 계층을 만들지 않는다. 같은 infrastructure라도 기술 소유권을 더 잘 드러낼 때
`kafka`, `redis`, `smtp`, `cache`처럼 구체적인 하위 package를 사용한다.

## 5. 주요 Subpackage와 대표 코드

| 경로 | 담당 역할 | 코드 유형 | 대표 클래스/파일 | 포함하지 않는 책임 |
|---|---|---|---|---|
| `admin/presentation` | 운영 HTTP API | Controller, HTTP DTO | `AdminMemberController`, `AdminMemberDetailResponse` | QueryDSL 구현 |
| `admin/application` | 운영 조회 조합 | Query Service, 내부 Result | `AdminMemberQueryService`, `AdminMemberResult` | 독자 JPA Entity |
| `admin/infrastructure/query` | Admin 전용 조회와 Repository fragment | Query interface/implementation | `AdminStatisticsRepositoryImpl` | API mapping |
| `auth/presentation` | 인증 HTTP API | Controller, Request/Response | `AuthController`, `LoginRequest` | JWT 구현 |
| `auth/application` | 인증 유스케이스와 인증 사용자 model | Service, Model | `AuthService`, `AuthMember` | Security filter chain |
| `auth/infrastructure` | JWT·Redis session·Spring Security | Provider, Store, Filter, Config | `JwtTokenProvider`, `RefreshTokenStore` | 회원 프로필 정책 |
| `member/presentation` | 회원 HTTP API | Controller, DTO | `MemberController`, `MemberResponse` | 인증 token 처리 |
| `member/application` | 회원 조회·수정 유스케이스 | Service | `MemberService` | HTTP mapping |
| `member/domain` | 회원 상태와 역할 | Entity, Enum, ErrorCode | `Member`, `MemberRole` | Security 구현 |
| `member/infrastructure` | 회원 persistence | Spring Data Repository | `MemberRepository` | Admin 조회 정책 |
| `reservation/presentation` | 예약·취소·노쇼 HTTP API | Controller, HTTP DTO | `ReservationController`, `ReservationPrepareRequest` | 예약 상태 변경 구현 |
| `reservation/application` | 예약 유스케이스와 외부 협력 계약 | Service, Result, Port | `ReservationPreparationService`, `ReservationCompletionTestHook` | HTTP mapping, JPA 구현 |
| `reservation/domain` | 예약·참여·노쇼 상태와 정원 정책 | Entity, Enum, Policy, ErrorCode | `Reservation`, `CancellationScope`, `ReservationCapacityPolicy` | Scheduler와 외부 결제 구현 |
| `reservation/infrastructure` | 예약 persistence와 기술 진입점 | Repository, Adapter, Scheduler | `ReservationRepository`, `ReservationClosingScheduler` | 정책·계약 재설계 |
| `payment/presentation` | 결제·환불·정산 조회 HTTP API | Controller, Response DTO | `PaymentController`, `PortOneWebhookController` | PortOne SDK 호출 구현 |
| `payment/application` | 결제·환불 유스케이스와 협력 계약 | Service, Command/Result, Port | `PaymentCompletionService`, `PortOnePaymentReader` | HTTP mapping과 SDK 설정 |
| `payment/domain` | Payment/Refund 상태와 오류 | Entity, Enum, ErrorCode | `Payment`, `Refund`, `PaymentStatus` | 별도 Settlement Entity |
| `payment/infrastructure` | PortOne·Repository·Scheduler 구현 | Adapter, Config, Repository, Scheduler | `PortOneSdkPaymentReader`, `PaymentExpirationScheduler` | 결제 정책 재결정 |
| `chat/presentation` | Chat HTTP/STOMP 표현 경계 | Controller, DTO, exception handler | `ChatMessageController`, `ChatExceptionHandler` | Kafka·Redis 처리 |
| `chat/application` | 메시지·신고·moderation 유스케이스 | Service, DTO, Event, Port, Exception | `ChatModerationService`, `ModerationAnalysisException` | Provider와 broker 구현 |
| `chat/domain` | ChatRoom/Message/Moderation 상태 | Entity, Enum, ErrorCode | `ChatRoom`, `ChatMessage`, `ChatErrorCode` | WebSocket 설정 |
| `chat/infrastructure/ai` | Chat AI provider 구현 | Prompt, Options, Adapter, Config | `SpringAiModerationAdapter` | RestaurantInsight AI 처리 |
| `chat/infrastructure/kafka` | Chat moderation Kafka 처리 | Consumer, Retry/DLT, Config | `ChatModerationConsumer`, `ChatModerationDltRecoverer` | RestaurantInsight consumer |
| `chat/infrastructure/outbox` | Chat 전용 Outbox 처리 | Processor, Scheduler, Dispatcher | `ChatMessageOutboxProcessor` | 범용 Outbox Entity |
| `chat/infrastructure/redis` | 인스턴스 간 실시간 전파 | Pub/Sub payload, Publisher, Subscriber | `RedisChatMessagePublisher` | 인증 token 저장 |
| `chat/infrastructure/websocket` | STOMP 연결·권한·전달 설정 | Config, Interceptor, Principal | `WebSocketConfig`, `ChatStompInterceptor` | HTTP API Controller |
| `restaurant/restaurant` | 식당 자체 기능 | 4계층과 cache/repository | `Restaurant`, `RestaurantService`, `RestaurantSearchCacheStore` | 회차·테이블 Entity |
| `restaurant/sharedtable` | 합석 테이블 기능 | 4계층과 사용 여부 Port | `SharedTable`, `SharedTableUsagePort` | 예약 persistence |
| `restaurant/timeslot` | 예약 가능 회차 기능 | 4계층과 사용 여부 Port | `TimeSlot`, `TimeSlotReservationValidator` | Payment 상태 관리 |
| `restaurant/image` | 식당 이미지 Key·S3 연동 | 4계층과 storage/config | `RestaurantImagePolicy`, `S3RestaurantImageStorageAdapter` | Lambda 내부 검증 구현 |
| `restaurantinsight/presentation` | OWNER Insight 조회 API | Controller, Response DTO | `RestaurantFeedbackInsightController` | Restaurant Controller endpoint |
| `restaurantinsight/application` | 피드백 분석·조회 유스케이스 | Service, Analysis DTO, Port | `RestaurantFeedbackInsightService` | Kafka listener와 AI SDK 설정 |
| `restaurantinsight/domain` | Insight/Item 상태와 필터 정책 | Entity, Enum, Policy | `RestaurantFeedbackInsight`, `RestaurantInsightPrivacyValidator` | ChatMessage persistence |
| `restaurantinsight/infrastructure` | AI·Kafka·persistence 구현 | Adapter, Consumer/DLT, Repository | `SpringAiRestaurantFeedbackInsightAdapter`, `RestaurantFeedbackInsightConsumer` | Chat moderation consumer |
| `notification/infrastructure/outbox` | Email 발송 의도와 수신자 처리 | Delivery Entity, Repository, Processor, Scheduler | `EmailOutboxEventService`, `EmailOutboxProcessor` | 범용 Outbox 상태 기반 |
| `notification/infrastructure/smtp` | Reservation 알림 SMTP 전달 | Adapter | `SmtpReservationNotificationAdapter` | Reservation 유스케이스 |
| `common/outbox` | 여러 기능이 공유하는 Outbox 기반 | Event/Status/Type, Repository, Transaction Service | `OutboxEvent`, `OutboxEventTransactionService` | Chat/Email 전용 Processor |
| `common/privacy` | 공통 개인정보 노출 제한 | Masking utility | `MemberNameMasker` | AI 전처리 전체 정책 |
| `common/exception` | 전역 오류 응답 기반 | ErrorCode contract, Exception, Advice | `CustomException`, `GlobalExceptionHandler` | 기능별 domain ErrorCode |
| `common/monitoring` | 공통 business metric 기록 | Event name, Recorder | `BusinessMetricRecorder` | Grafana dashboard 설정 |
| `common/transaction` | 공통 transaction 후속 실행 | Utility | `AfterCommitExecutor` | 기능별 Transaction 경계 결정 |

## 6. docs 구조

| 경로 | 문서 역할 | 대표 문서 |
|---|---|---|
| `docs/010-product` | 서비스 정책·역할·상태 | `project-context.md` |
| `docs/020-api` | HTTP/WebSocket API 계약 | `bobfull-api-spec-complete.md` |
| `docs/030-data` | 데이터 모델과 정합성 | `erd.md` |
| `docs/040-architecture` | 논리 구조, 의존 관계와 ADR | `architecture.md`, `domain-dependencies.md`, `adr/` |
| `docs/050-engineering` | GitHub, 코드와 테스트 규칙 | `github-rules.md`, `test-convention.md` |
| `docs/060-ai` | AI workflow, 구현·검토·Task Guide | 아래 AI 문서 구조 참고 |
| `docs/070-deployment` | 배포 절차와 환경 | `aws-v1-backend.md` |
| `docs/080-operations` | 운영·모니터링 기준 | `monitoring-runbook.md`, `refund-reconciliation-runbook.md` |
| `docs/090-testing` | 테스트 환경과 실행 안내 | `performance/k6-aws-test-environment.md` |
| `docs/100-learning` | 학습용 흐름과 시각화 | `system-flow/` |
| `docs/110-records` | Sprint 기록과 Evidence | `evidence/refactoring`, `evidence/v3` |
| `docs/120-templates` | 문서 작성 template | Issue/Evidence 보조 template |

## 7. ops 구조

| 경로 | 담당 역할 | 대표 파일 |
|---|---|---|
| `ops/deployment/aws` | ECR, EC2, SSM, Blue-Green 배포 script | `deploy-backend-blue-green-v1.sh` |
| `ops/monitoring` | Prometheus/Grafana Compose와 provisioning | `docker-compose.yml`, `prometheus.yml` |
| `ops/load-test` | k6 공통 module, fixture와 scenario | `common/config.js`, `scenarios/restaurant-search.js` |
| `ops/tools` | 저장소 유지보수 도구 | `check-markdown-links.ps1` |

운영 자산의 내용과 실행 계약은 각 디렉터리 README 및 deployment/operations 문서를 따른다.

## 8. AI 작업 문서 구조

| 경로 | 역할 | 사용 시점 |
|---|---|---|
| `AGENTS.md` | 공통 안전 경계와 문서 routing | 모든 BobFull AI 작업의 첫 진입점 |
| `docs/060-ai/workflow` | Issue 상태 흐름과 Refactor Learning Mode | Issue 흐름·Human 판단 확인 |
| `docs/060-ai/development` | 확정 계약의 구현·검증 절차 | `status:in-progress` 이후 |
| `docs/060-ai/review` | Human 답변과 별도 검토 기준 | Human 이해 검토가 필요할 때 |
| `docs/060-ai/tasks` | Onboarding, PR 설명, PR Review Task Guide | 해당 작업 단계 진입 시 |

Task Guide는 저장소 workflow 문서이며 자동 발견 Agent Skill이 아니다. 새로운 AI 도구를 연결할 때는 파일명이나
경로를 임의로 바꾸지 말고 `AGENTS.md`의 Context Loading 표를 먼저 갱신한다.

## 9. 변경 시 확인

- 파일을 이동하면 package declaration, import, test reference와 문서 링크를 함께 갱신한다.
- 기능 전용 기술 구현은 해당 기능의 infrastructure에 두고, 실제 공유 기반만 common에 둔다.
- 위치 이동만으로 해결되지 않는 책임·의존성 문제는 Issue #20에서 별도로 검토한다.
- 타입 이름과 `application/dto`·`application/model`, Entity/Enum 기준은 Issue #15에서 정리한다.
- 구조를 바꾼 Issue는 `docs/110-records/evidence/refactoring`에 Before/After와 동작 보존 결과를 기록한다.
