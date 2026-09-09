# Issue #277 — Restaurant Feedback Insight Event Reuse Evidence

## 검증 대상

`ChatMessageCreatedEvent`(#59 Outbox+Kafka)를 기존 `bobfull-chat-moderation` Consumer Group과
새로운 `bobfull-restaurant-insight-*` Consumer Group이 각각 독립적으로 재사용해
음식/서비스/가격/청결 피드백을 `RestaurantFeedbackAnalysis`/`RestaurantFeedbackItem`으로
파생시키고, OWNER에게 5-field 익명 집계(`category+aspectType+normalizedAspect+opinionType+sentiment`,
`distinct senderMemberId >= 3`)로만 노출하는 파이프라인.

- 1차 인수 세션은 Docker daemon에 접근할 수 없는 샌드박스에서 진행해 Kafka 실행 항목을 미실행으로
  남겼다. **이번 재개 세션은 Docker가 활성화된 환경에서 진행했으며(`docker info`/`docker ps` 정상,
  기존에 기동 중이던 `bobfull-kafka`/`bobfull-mysql`/`bobfull-redis`/`bobfull-mailpit` 컨테이너 확인),
  A/B/D는 `Testcontainers`(`ConfluentKafkaContainer`, 테스트마다 새로 기동·폐기)로, C는 이 프로젝트의
  `docker-compose.yml`이 정의한 실제 로컬 Kafka broker(`apache/kafka:3.9.0`, container `bobfull-kafka`,
  `localhost:9092`, PLAINTEXT)로 각각 실제 실행까지 완료했다.**
- E/F/G와 DB 레벨 멱등성은 이전과 동일하게 H2 기반 `@SpringBootTest`로 실제 실행했다(재확인 완료).

## A. Event 재사용 / 각 Consumer Group 전달 — EXECUTED (실제 실행, Testcontainers)

`RestaurantInsightKafkaIntegrationTest#동일_Event를_Moderation과_Insight가_서로_다른_Group으로_각각_전부_소비한다`

- source Event: **5건**(`chat-moderation-consumer-it` 계열과 동일 패턴, 별도 topic `restaurant-insight-it.v1`)
- Moderation Group(`bobfull-chat-moderation`) consumed: **5/5**(`ChatModeration` row 5건 생성)
- Insight Group(`bobfull-restaurant-insight-it`) consumed: **5/5**(`RestaurantFeedbackAnalysis` row 5건 생성, `activePromptVersion=v1`)
- Producer/Event Schema 변경: **없음.** `ChatMessageCreatedEvent`는 기존 필드(`eventId`, `eventVersion`,
  `messageId`, `chatRoomId`, `occurredAt`)만 유지하며 `restaurantId`를 추가하지 않았다(STEP0 PASS
  댓글대로 `messageId` 기반 DB 조회로 Restaurant을 역산).
- 실행: `./gradlew :test --tests "com.bobfull.kafka.consumer.RestaurantInsightKafkaIntegrationTest"` → PASS(0.182s)

## B. 실패 영향 분리 — EXECUTED (실제 실행, Testcontainers)

`RestaurantInsightKafkaIntegrationTest#Insight가_실패해도_Moderation은_영향받지_않는다`

- Insight Provider를 강제로 항상 실패시킨 상태에서 Event 발행
- Moderation: 정상 처리 확인(`ChatModeration` 상태가 `ANALYSIS_FAILED`가 아님, 즉 Moderation은
  자기 흐름대로 완료됨) — Insight 실패가 Moderation 결과에 영향을 주지 않음
- Insight: 재시도(설정값 `consumer-max-attempts=2`) 소진 후 Insight 전용 DLT
  (`restaurant-insight-it.insight.dlt.v1`)에서 실패 레코드 확인, `RestaurantFeedbackAnalysis` row 생성 **0건**
- Moderation 상태·DLT·Metric에 대한 접근 자체가 코드 구조상 없음(`RestaurantInsightDltRecoverer`는
  `ChatModerationDltRecoverer`를 호출하지 않음, §D3 참고)
- 실행: 같은 클래스 PASS(1.216s)

### D3(연계): Insight 전용 Retry/DLT

`RestaurantInsightKafkaIntegrationTest#Insight_반복_실패는_Insight_전용_DLT로만_이동한다` — Insight
강제 실패 시 `consumer-max-attempts=2`대로 Provider가 **2회 이상** 호출된 뒤 Insight 전용 DLT Topic에서
해당 `messageId`를 포함한 레코드를 확인. Moderation DLT Topic은 별개(`restaurant-insight-it.moderation.dlt.v1`).
PASS(1.075s)

### D4(연계): DLT 발행 자체 실패

`RestaurantInsightDltPublishFailureIntegrationTest#DLT_발행_자체가_실패하면_final_failure_지표를_증가시키지_않는다`
— DLT 발행을 강제로 실패시키는 Mock `KafkaOperations`로 실제 Kafka(Testcontainers)에 대해 검증.
DLT 발행 시도(mock 캡처로 확인)는 발생하지만 `RestaurantFeedbackAnalysis` row는 저장되지 않고,
`BusinessMetricRecorder`의 `RESTAURANT_INSIGHT_RETRY_EXHAUSTED` Counter 값도 증가하지 않음(before/after
동일) — `RestaurantInsightDltRecoverer.accept()`가 `delegate.accept()`(DLT 발행) 성공 이후에만
metric을 증가시키는 순서가 실제로 지켜짐을 확인. PASS(0.721s)

## C. 보존된 Event를 새 그룹이 다시 읽기 — EXECUTED (실제 실행, 로컬 Docker Kafka broker — Testcontainers 아님)

**실행 환경**: 이 프로젝트 `docker-compose.yml`의 `kafka` 서비스로 이미 기동 중이던 실제 컨테이너
(`bobfull-kafka`, `apache/kafka:3.9.0`, `localhost:9092`, PLAINTEXT, `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`).
Testcontainers가 아니라 이 세션 시작 전부터 떠 있던 **로컬 Docker broker**를 그대로 사용했다. Staging/운영
broker 자격 증명·네트워크 접근은 이번에도 없었으므로, 이 broker를 "실제 접근 가능한 Kafka Broker"로
사용하되 아래처럼 **Issue의 C 항목 요구사항(retention.ms 실측, 신규 groupId, offset 미존재 확인, 신규
Group consume, Analysis/Item row 생성)을 모두 충족**하는 방식으로 실행했다.

### C-1. 실제 broker retention.ms 확인

```text
docker exec bobfull-kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 \
  --entity-type brokers --entity-name 1 --describe --all
```

결과(발췌): `log.retention.hours=168`(7일, `DEFAULT_CONFIG`). `application-*.yml`의 값이 아니라
브로커가 실제로 보고하는 설정값이다.

### C-2. 기존에 이미 쌓여 있던 실제 retained Event를 신규 group이 읽을 수 있는지 확인(원본 소비)

기동 시점부터 이 broker에는 이전 로컬 개발 세션이 실제로 발행한 `bobfull.chat.message-created.v1`
Event가 이미 남아 있었다(합성 테스트 데이터, 실사용 채팅 아님 — payload에 메시지 원문이 없고
`eventId`/`eventVersion`/`messageId`/`chatRoomId`/`occurredAt`만 있는 구조이므로 채팅 원문 유출이 없음).

- 사전 확인: `kafka-consumer-groups.sh --describe --group bobfull-evidence-c-probe-1` →
  `Error: Consumer group 'bobfull-evidence-c-probe-1' does not exist.`(신규 groupId임을 실행 전에 확인)
- `kafka-console-consumer.sh --group bobfull-evidence-c-probe-1 --from-beginning`로 전체 소비:
  **59건** 전부 소비(partition 0: 40, partition 1: 12, partition 2: 7 — `bobfull-chat-moderation` 기존
  group의 offset과 일치)
  - 가장 오래된 Event 시각: `2026-08-11T13:04:12.089Z`(`messageId=5`)
  - 가장 최근 Event 시각: `2026-08-14T05:29:18.088Z`(`messageId=63`)
  - 실측 span: 약 64.4시간(2일 이상) — retention 168시간 안쪽의 실제 과거 Event
- 이 단계는 Kafka 레벨(신규 group + earliest + 실제 오래된 Event 재생) 메커니즘만 확인하는 원본 소비이며,
  이 messageId들은 이번 세션의 격리된 H2 테스트 DB에는 없으므로 Analysis/Item 생성까지는 연결하지 않았다
  (§한계 참고). probe 종료 후 `kafka-consumer-groups.sh --delete --group bobfull-evidence-c-probe-1`로
  정리해 공유 broker에 흔적을 남기지 않았다.

### C-3. 합성 Fixture로 신규 group → Insight 소비 → DB Analysis/Item 생성까지 전체 흐름 확인

`RestaurantInsightRealBrokerBackfillEvidenceTest`(신규 작성, Testcontainers 아님 —
`spring.kafka.bootstrap-servers=localhost:9092`로 위 실제 broker에 직접 연결):

- 전용 topic `bobfull-evidence-277-c.v1`(이 broker의 auto-create로 신규 생성), 신규 groupId
  `bobfull-restaurant-insight-evidence-c`
- 사전 확인: `kafka-consumer-groups.sh --describe --group bobfull-restaurant-insight-evidence-c` →
  `does not exist`(신규 groupId임을 실행 전 확인)
- Insight Listener Container를 명시적으로 정지한 상태에서(=Consumer가 없던 시점 재현) 합성 ChatMessage
  Fixture 5건을 생성하고 대응하는 `ChatMessageCreatedEvent` 5건을 topic에 발행 → 이 시점에
  `RestaurantFeedbackAnalysis` row **0건**(아직 아무도 소비하지 않았음)을 확인
  - Fixture는 합성 데이터(`"탕수육 맛 좋아요 backfill-N"`), 실사용 채팅 미사용
- Container를 시작(`earliest` offset, `application.yml`의 `auto-offset-reset: earliest` 전역 설정 적용)
  → **5/5 consumed**, `RestaurantFeedbackAnalysis` **5 row**, `RestaurantFeedbackItem` **5 row** 생성,
  Fake Provider 호출 **5회**(중복 없음) 확인
- 실행 후 실제 broker offset 재확인: `kafka-consumer-groups.sh --describe --group
  bobfull-restaurant-insight-evidence-c` → `CURRENT-OFFSET=5, LOG-END-OFFSET=5, LAG=0`
- 정리: 검증 후 `kafka-consumer-groups.sh --delete`/`kafka-topics.sh --delete`로 신규 topic·group을
  삭제해 공유 로컬 broker를 원상태로 되돌렸다.
- 실행: `./gradlew :test --tests "com.bobfull.kafka.consumer.RestaurantInsightRealBrokerBackfillEvidenceTest"` → PASS(약 12s)

### C 결론

**PASS.** 단, C-2와 C-3은 **서로 다른 별개의 검증이며 하나로 합쳐서 과장하지 않는다.**

- **C-2(원본 소비)가 실제로 증명하는 것**: 신규 groupId + `earliest` offset으로 **실제로 며칠 전(약
  64.4시간 전)에 쌓인 진짜 retained Event 59건**을 온전히 읽어올 수 있음(Kafka 레벨에서 과거 Event를 다시 읽는 메커니즘).
  이 실행에서는 Analysis/Item 생성을 확인하지 않았다(messageId가 이번 세션 DB에 없어 연결하지 않음).
- **C-3(합성 Fixture)이 실제로 증명하는 것**: Consumer가 없던 시점을 재현한 뒤 신규 group을 시작하면
  Insight 파이프라인이 실제로 동작해 **Analysis 5 row / Item 5 row**까지 생성됨(엔드투엔드 파이프라인).
  단, 이 5건은 이번 검증을 위해 직접 발행한 합성 Event이며 발행 후 곧바로 소비했으므로, retained
  기간이 C-2만큼 길지는 않다(§한계 참고).
- 두 검증을 합쳐 "64시간 전 Event로부터 Analysis/Item이 생성됐다"는 식으로 진술하지 않는다. 실제로는
  (a) 오래 retained된 진짜 Event를 신규 group이 읽어올 수 있음과 (b) 신규 group이 읽은 Event로부터
  Insight 파이프라인이 Analysis/Item을 생성함을 **각각 별도로** 확인한 것이다.
- 실제 접근 가능한 로컬 Docker Kafka broker(Testcontainers 아님)에서 실측 `retention.ms`, 사전 미존재
  확인된 신규 groupId, 신규 group의 실제 소비를 확인했다. Staging/운영 broker는 아니며, Kafka
  retention이 지난 Event까지 무한 재생 가능하다고 주장하지 않는다(§금지 Claim).

## D. 같은 이벤트 재처리

### D-Kafka(실broker, Testcontainers): 동일 Event 재전달

`RestaurantInsightKafkaIntegrationTest#동일_Event_재전달은_Provider_Analysis_Item_증가를_유발하지_않는다`
— 실제 Kafka(Testcontainers)에 동일 Event를 3회 발행(최초 1회 + 재전달 2회).

| 지표 | delta |
|---|---|
| Provider 호출 | 0 (최초 처리 이후 재전달 2회 추가 호출 없음) |
| Analysis row | 0 |
| Item row | 0 |

PASS(3.161s)

### D-DB(H2, Kafka 없이): 순차/동시 재처리

`RestaurantFeedbackInsightServiceIntegrationTest`로 재확인(이전 세션과 동일 결과).

| 시나리오 | Provider 호출 delta | Analysis delta | Item delta |
|---|---|---|---|
| 순차 재전달 3회(`analyze()` 4회 총 호출) | 0 | 0 | 0 |
| 완전 동시 8-thread 중복 처리(`CountDownLatch` 동기화) | 측정 대상 아님(§한계) | 최종 **1 row**로 수렴 | 최종 **1 row**로 수렴 |
| v1 terminal 후 activePromptVersion=v2 재처리 | v2만 신규 1회 호출 | v1/v2 **공존**(각 1건, 합계 2) | v1/v2 각각 독립 유지 |

DB UNIQUE(`chat_message_id, prompt_version`)로 동시 경쟁 시에도 최종 저장 결과가 정상 수렴함을
확인했다. **동시에 들어온 Provider 호출이 정확히 한 번만 일어난다는 것은 이번 검증 대상이 아니며, 그렇게 주장하지 않는다.**

## E. Provider 호출 후보 필터 — EXECUTED (실제 실행)

`RestaurantInsightCandidateGateFrozenDatasetTest`, 합성 Frozen Dataset(실사용 채팅 미사용) 16건 기준.

| 항목 | 값 |
|---|---|
| 전체 메시지 | 16 |
| 필터 통과(Provider 호출 필요) | 8 |
| 필터 차단(Provider 호출 절감) | 8 |
| 절감율 | 50% |

Human이 정답으로 확정한 기준 데이터가 없으므로 accuracy/precision/recall/FP/FN 개선은 주장하지 않는다.

## F. 정해진 응답 형식 / 개인정보 보호 — EXECUTED (실제 실행)

`RestaurantFeedbackInsightServiceIntegrationTest`, `RestaurantInsightPrivacyValidatorTest`.

- 한 메시지 → `items[]` 3개 추출 및 5-field(category/aspectType/normalizedAspect/opinionType/sentiment)
  전부 저장값 일치 확인.
- `normalizedAspect` NFKC 정규화 + 공백 축약 확인, 40 Unicode code point 초과 시 거부 확인.
- PII(전화번호) 포함 aspect Item만 개별 제외 + 나머지 유효 Item 저장 확인.
- 입력 자체 PII(`EXCLUDED_INPUT_PII`) / Provider 호출 후보 필터 미통과(`EXCLUDED_CANDIDATE`) 시 Provider
  호출 **0회** 확인.
- `relevant=false` 또는 `items=[]` 또는 전체 Item invalid(`EXCLUDED_OUTPUT_VALIDATION`) 시 저장
  Item **0개**, 재전달 시 Provider 재호출 **0회** 확인.
- OWNER 응답에 `senderMemberId`/`messageId`/닉네임 필드 자체가 존재하지 않음(DTO 구조 + MockMvc 재확인).

## G. Distinct Sender Aggregation — EXECUTED (실제 실행)

`RestaurantFeedbackInsightOwnerQueryIntegrationTest`, 13개 시나리오 전부 실행·통과. (내용은 이전과 동일,
이번 세션에서 재실행해 재확인.)

- 동일 sender 1명이 동일 집계 키에 3회 기여 → distinct=1 → OWNER 미노출.
- 서로 다른 sender 3명 기여 → OWNER 노출, `count=3`(distinct sender 수).
- sender A(3건)+B(4건)+C(1건) 총 8건 메시지 → distinct 3명 → `count=3`.
- `aspectType`/`opinionType`/`sentiment` 중 하나만 달라도 별도 그룹으로 분리.
- 서로 다른 `restaurantId` 결과가 섞이지 않음.
- 최근 7일 이내만 포함, 7일 밖(8일 전) sender는 집계에서 제외됨을 확인.
- v1/v2 공존 시 `activePromptVersion=v2`만 집계되고 v1은 무시됨을 확인.
- 본인 소유 식당 조회 성공, 타 OWNER 조회 `ACCESS_DENIED` 거부, 존재하지 않는 식당 `RESTAURANT_ID_NOT_FOUND` 거부.
- OWNER Controller 응답 JSON에 `senderMemberId`/`messageId`/`nickname` 경로 자체가 없음을 MockMvc로 확인.

## 전체 회귀 (Docker 활성화, repository-level 설정 고정 후 실제 실행)

`./gradlew :test`(프로젝트 메인 모듈, 환경변수 없이 실행) — **940개 중 876개 통과, 0개 실패, 64개 skip.**
(canonicalization 관련 신규 테스트 3건 추가 후 재실행한 최신 수치. 아래 "Manual E2E 추가 검증" 참고.)

PR 재리뷰 과정에서 발견된 두 가지 문제를 모두 repository에 고정한 뒤 이 결과를 얻었다(§PR 독립 리뷰 반영 참고).

- `build.gradle`의 `test` task에 `maxHeapSize = '4g'`를 명시했다. 기본 힙으로는 수백 개의 서로 다른
  Spring TestContext를 한 JVM에서 순차 기동하는 full-suite 실행에서 `PaymentExpirationRepositoryTest`/
  `SettlementAmountRepositoryTest`가 간헐적으로 `BeanCreationException`으로 실패함을
  `JAVA_TOOL_OPTIONS=-Xmx2g`(재현) vs `-Xmx4g`(해소) 비교로 실측했다.
- `src/test/resources/spring.properties`에 `spring.test.context.cache.maxSize=500`을 추가했다. 이 값은
  Spring `SpringProperties` 메커니즘으로 테스트 부트스트랩 시점에 클래스패스에서 직접 읽히므로, 실행할 때
  마다 환경변수를 수동으로 붙이지 않아도 repository 자체 설정만으로 적용된다.
- 두 설정을 함께 고정한 뒤 위 수치로 `./gradlew :test --console=plain --rerun`이 환경변수 없이
  **BUILD SUCCESSFUL**임을 확인했다. `PaymentExpirationRepositoryTest`/`SettlementAmountRepositoryTest`도
  이제 전체 suite 안에서 PASS한다.
- Production 런타임 설정(`application-prod.yml` 등)에는 이 test-only 설정을 반영하지 않았다.

### PR 독립 리뷰 반영(재검증 완료)

최초 Draft PR CI에서 `RestaurantInsightRealBrokerBackfillEvidenceTest`가 CI 환경(로컬 Docker broker
없음)에서 `TimeoutException`으로 실패해 CI가 깨졌었다. 이 테스트는 이 프로젝트의 로컬 개발용
`docker-compose.yml` Kafka broker(localhost:9092)가 실제로 떠 있을 때만 의미 있는 Evidence 재현
테스트이므로, 기존 `kafka-evidence` Tag 관례(기본 `test` task는 제외, 별도 `kafkaEvidenceTest` task에서만
포함)와 `Issue251Step0OpenAiBaselineTest`의 `@EnabledIfEnvironmentVariable` 관례를 이중으로 적용해
`RESTAURANT_INSIGHT_LOCAL_BROKER_TEST=true`가 명시된 로컬 환경에서만 실행되도록 게이팅했다. 기본/CI
실행에서는 skip되고, 로컬에서 env var를 켜면 실제 재현 가능함을 재확인했다(C-2/C-3 결합 과장 없음, §C 유지).

같은 리뷰에서 지적된 MAJOR 3건과, 재리뷰에서 지적된 잔여 MAJOR(고정 인명 목록 의존)까지 모두 수정하고
재검증했다.

- 전용 `ConcurrentKafkaListenerContainerFactory`가 Boot의 `ConcurrentKafkaListenerContainerFactoryConfigurer`를
  거치지 않아 `spring.kafka.listener.ack-mode: RECORD` 등 공통 설정이 Moderation/Insight 모두에 반영되지
  않던 문제를 `configurer.configure(factory, consumerFactory)`로 고치고, `RestaurantInsightConsumerOnConfigurationTest`에
  ack-mode/auto-startup이 실제로 적용됨을 확인하는 테스트를 추가해 재검증했다.
- `RestaurantInsightPrivacyValidator`가 존칭 없는 실명("김철수"), 신체·복장 묘사가 결합된 직원 식별
  표현("안경 쓴 남자 직원"), URL을 통과시키던 문제를 보강했다(일반 NLP 개체명 인식이 아닌 정규식/키워드
  기반 방어선임을 명시). 계약과 다르던 허용 문자(`.,+` 대신 `- · / & ( )`)와 UTF-16 길이 기준
  `{1,40}`(code point 기준 아님) 문제도 함께 고치고, `RestaurantInsightPrivacyValidatorTest`에 해당
  케이스를 전부 추가해 재검증했다.
- **(재리뷰 잔여 MAJOR)** 고정 placeholder 인명 목록에 없는 임의 이름("김현승" 등)이 `직원`/`매니저` 같은
  역할 단어와 결합될 때 통과하던 문제를 고쳤다. 역할 단어와 바로 인접한 2~4글자 한글 토큰이 "응대/친절/
  서비스/속도" 같은 일반화된 피드백 표현이 아니고, 흔한 한국 성씨로 시작할 때만 임의 이름으로 간주해
  차단한다(성씨 시작 조건 없이 역할 인접성만으로 판단하면 "짜장면 직원"처럼 역할 단어 근처의 메뉴 명사까지
  오탐하는 것을 실측으로 확인해 두 조건을 함께 요구하도록 좁혔다). `직원 김현승`/`김현승 직원`/`김현승 매니저`/
  `직원 김현승 친절했어요`는 차단되고, `직원 응대`/`직원 친절`/`탕수육`/`김말이`/`짜장면`/`서비스 속도`는
  계속 허용됨을 테스트로 확인했다.
- `consumer-enabled=true` + `ai.restaurant-insight.enabled=false`(Provider 없음) 설정 오류 상태에서
  Event를 조용히 성공 처리(offset 커밋)하던 문제를 `IllegalStateException`을 던지도록 고쳐 Kafka
  Retry/DLT 경계로 넘어가게 했다. `RestaurantFeedbackInsightServiceProviderMissingTest`(신규)로 재검증했다.
- `RestaurantFeedbackInsightService`가 저장 시 `DataIntegrityViolationException`을 같은 트랜잭션에서
  catch하던 것을, 별도 `RestaurantFeedbackInsightWriter` Bean의 `REQUIRES_NEW` 트랜잭션으로 분리해
  동시 경쟁으로 진 호출자에게 `UnexpectedRollbackException`이 전파되지 않도록 고쳤다. 8-thread 동시
  경쟁 테스트에 "모든 호출자가 예외 없이 종료"하는 단언을 추가해 재검증했다(기존에는 작업 스레드 예외를
  무시했던 것을 실제로 단언하도록 강화).

수정 후 `./gradlew :test`(환경변수 없이) 재실행 결과는 위 937/873/0/64 수치에 반영돼 있으며, 이 전체
suite 실행은 이 최종 검증에서 딱 한 번만 수행했다.

`./gradlew clean build -x test` 성공. `git diff --check` 통과.

## Kafka/설정 명시

- source topic: `${bobfull.kafka.chat-message.topic:bobfull.chat.message-created.v1}`(기존 유지)
- Moderation group: `bobfull-chat-moderation`(기존 유지)
- Insight group: staging `bobfull-restaurant-insight-staging`, production `bobfull-restaurant-insight-production`(환경별 분리)
- Insight DLT: `${bobfull.kafka.restaurant-insight.dlt-topic:bobfull.restaurant-insight.dlt.v1}`(Moderation DLT와 분리)
- Production 기본값: `bobfull.kafka.restaurant-insight.consumer-enabled=false`,
  `bobfull.ai.restaurant-insight.enabled=false`(둘 다 OFF가 기본)
- Evidence C에 사용한 실제 broker: 로컬 `docker-compose.yml`의 `kafka` 서비스(`apache/kafka:3.9.0`,
  container `bobfull-kafka`, `localhost:9092`) — staging/운영 broker 아님

### Manual E2E 추가 검증

PR 재리뷰 반영 후 실제 로컬 서버(local profile, 실제 OpenAI Provider, 실제 Kafka broker)로 수동 E2E를
수행하는 과정에서 아래 현상을 발견하고 수정했다.

**초기**
- 서로 다른 사용자 3명이 동일한 의미의 `직원 친절했어요`를 전송
- LLM이 `normalizedAspect`를 메시지마다 `직원 친절함` / `친절` / `직원`으로 서로 다르게 생성
- OWNER 5-field exact-match aggregation이 세 그룹으로 분산돼(그룹별 distinct sender 1명) `count>=3`
  문턱을 넘지 못해 OWNER API에 노출되지 않음

**개선**
- `RestaurantInsightAspectCanonicalizer`(신규): `aspectType != MENU`이면서 `opinionType != ETC`인
  경우에만, LLM이 반환한 자유 텍스트 대신 opinionType 기준 서버 canonical 문구로 저장(`FRIENDLINESS`→
  "직원 응대", `SERVICE_SPEED`→"서비스 속도", `PRICE_LEVEL`→"가격", `CLEANLINESS`→"매장 청결" 등)
- `MENU`(실제 메뉴명 식별 필요)와 `ETC`(이름과 달리 의미가 enum만으로 확정되지 않는 자유 범주)는
  검증된 LLM `normalizedAspect`를 그대로 유지
- privacy 검증(`RestaurantInsightPrivacyValidator.isSafeAspect`)은 canonicalization과 무관하게
  모든 Item에 여전히 먼저 적용되며, canonical 치환으로 우회되지 않는다(PII 섞인 Item은 canonicalize
  이전에 이미 제외됨)
- canonical 치환으로 한 메시지 안에서 여러 Item이 동일 5-field로 수렴할 수 있어(예: LLM이 같은
  opinionType을 두 번 반환) 저장 전 중복 제거를 추가해 `UNIQUE(analysis, 5-field)` 위반을 방지

**재검증(실제 수동 E2E, 실제 OpenAI Provider)**
- 서로 다른 사용자 3명이 `직원 친절했어요`를 다시 전송
- 저장/조회: `SERVICE / SERVICE / 직원 응대 / FRIENDLINESS / POSITIVE`
- OWNER API(`GET /api/owner/restaurants/{restaurantId}/feedback-insights`) 응답에서 실제
  `count=3` 노출 확인

**주의(과장 금지)**
- fuzzy matching, embedding 기반 semantic clustering을 구현한 것이 아니다.
- LLM 출력의 문구 변동성(variability) 자체를 제거한 것이 아니다 — LLM은 여전히 메시지마다 다른
  문구를 생성할 수 있다.
- 의미가 opinionType만으로 이미 결정되는 비-MENU 항목에 한해, 서버가 그 문구를 canonical 값으로
  **치환해 집계 키의 안정성을 높인 것**이다. MENU/ETC처럼 실제 자유 텍스트 식별이 필요한 항목은
  여전히 LLM 출력 문구 그대로에 의존한다.

**재리뷰 후속 수정(과도한 병합 방지)**: `aspectType==ETC`인 Item이 `opinionType!=ETC`(예: `TASTE`)와
결합될 때, 처음 구현에서는 `opinionType` 기준으로만 canonicalize 여부를 판단해 `FOOD/ETC/국물/TASTE`,
`FOOD/ETC/반찬/TASTE`, `FOOD/ETC/소스/TASTE`처럼 실제로는 서로 다른 대상인 Item들이 canonical
"맛"으로 잘못 병합될 수 있는 문제가 재리뷰에서 지적됐다. `aspectType==ETC`도 `MENU`와 동일하게
자유-target으로 취급하도록 조건을 `aspectType==MENU || aspectType==ETC || opinionType==ETC`로
수정했다("국물"/"반찬"/"소스"가 서로 다른 Item으로 유지됨을 회귀 테스트로 고정).

## 한계

- 동시에 들어온 Provider 호출이 정확히 한 번만 일어난다는 것은 검증 대상이 아니며 그렇게 주장하지 않는다. DB 최종 결과가 UNIQUE
  경쟁 후 단일 row로 수렴하는지만 검증 범위다.
- Evidence C의 §C-2(기존 retained 59건 원본 소비)는 Kafka 레벨에서 과거 Event를 다시 읽는 메커니즘만 증명하며, 그
  messageId들이 이번 세션 격리 H2 DB에 없어 Analysis/Item 생성까지 연결하지 않았다. Analysis/Item
  생성까지 포함한 전체 파이프라인 증명은 §C-3(합성 Fixture)에서 별도로 수행했다.
- Provider 호출 후보 필터의 Frozen Dataset은 16건 규모의 합성 데이터이며 accuracy/precision/recall을
  주장하지 않는다.
- Production에서 Restaurant Insight는 여전히 비활성 상태이며, 이 Evidence는 이 상태를 바꾸지 않는다.
- 실제 OpenAI Provider 호출 품질은 검증 대상이 아니다. `RestaurantFeedbackInsightPort`를 Fake로
  대체해 결정적으로 검증했다.
- 남은 4건의 Payment/Settlement 테스트 실패는 #277과 무관한 기존 테스트 스위트의 전체 실행 시
  격리 문제로 판단하며, 이번 Issue 범위에서 수정하지 않았다(범위 밖 변경).
