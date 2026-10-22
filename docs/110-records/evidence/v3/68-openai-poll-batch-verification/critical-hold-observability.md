# Critical Native HOLD 분석 및 무과금 관측 검증

## 범위와 판정

- Issue #68 후속 분석. 실행 기준 HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807` + 기존 미커밋 작업 + 이번 테스트/로컬 수집 변경. 새 Commit 없음.
- 실제 OpenAI 재호출, API Key 읽기/사용, Production 변경 없음. 기존 GT/Metadata/Prompt/Policy/Rule/Model과 Gate V1/V2 HOLD 유지. Batch 기본 OFF 유지.
- 아래 새 실행은 **Fake HTTP + 격리 Kafka/MySQL** 계약 검증이다. 모델 품질, 실제 Token 비용, 운영 지연을 입증하지 않는다.
- 이전 [Live 결과](expanded-pilot-live.md), [원본 요약](expanded-pilot-live-results.json), 승인 Manifest, private journal/checkpoint를 덮어쓰지 않는다. 새 코드가 과거 관측 누락을 복원했다는 의미가 아니다.

## 1. Critical 위험도 하향

### 확인된 사실

`HOLDOUT-PROFANITY-14`: “한 번만 더 그러면 가만 안 둔다”. 동결 GT `FLAGGED / [PROFANITY] / HIGH`; 이번 Native 실제 응답의 저장 결과는 `FLAGGED / [PROFANITY] / MEDIUM`이다. Critical minimum HIGH에 미달하므로 `CRITICAL_RISK_DOWNGRADE`로 중단했다. Review 신호가 유지됐다는 이유로 이 보호 실패를 비차단으로 바꾸지 않는다.

이번 Pilot은 Native 논리 1회 / HTTP 1회에서 중단했고 Batch 요청은 0회다. 따라서 **이번 실행의 Batch 고유 회귀는 측정되지 않았다**.

PR #60에 포함된 #57 Raw와 비교:

| 과거 실험 | 실행 시작 UTC (2026-10-20) | Native | Batch |
|---|---|---|---|
| `microbatch-comparison.json` | 05:31:25 | 1회 MEDIUM | 2/5/10 각 MEDIUM |
| `microbatch-f0.json` | 06:10:26 | 3회 모두 MEDIUM | 5/10 각 3회 모두 MEDIUM |
| `microbatch-format-a.json` | 07:00:09 | 2회 모두 MEDIUM | Batch 1: MEDIUM, HIGH |
| `microbatch-size-b.json` | 07:24:57 | 별도 Native 없음 | 2/5/10 각 2회 MEDIUM; 20은 SAFE/LOW, FLAGGED/MEDIUM |
| 이번 Live | 2026-10-22 01:54:02~01:54:29 (Gradle/Container 포함) | MEDIUM | 미실행 |

위 Raw에서 기존 Native **6회 모두 MEDIUM**은 이 사례에서도 확인된다. Format A의 Batch 1 HIGH 1회와 Size B의 Batch 20 SAFE 1회는 요청 형식/구성 및 반복 변동의 존재를 보여주지만, 하나의 원인을 식별하는 통제 실험은 아니다. Q1/Checklist 등 미채택 Prompt 결과는 Production Prompt 기준선과 합산하지 않는다.

원본: [비교](../55-ai-moderation-efficiency/microbatch-comparison.json), [F0](../55-ai-moderation-efficiency/microbatch-f0.json), [형식 A](../55-ai-moderation-efficiency/microbatch-format-a.json), [크기 B](../55-ai-moderation-efficiency/microbatch-size-b.json). 과거와 이번은 실행 시점, Kafka/DB 유무, 입력 배치가 다르다.

### 요청 및 저장 계약 대조

| 항목 | 확인 |
|---|---|
| 원문/GT | 기존 Policy v2 Case와 Live summary가 동일. GT 자동 수정 없음 |
| Prompt | PR #60 참조 Commit `157bac915b869cb9b1f306175cf54acd57d2f13c`의 Production 파일과 현재 파일 SHA-256 동일: `8a57a13bcccc1e76f17bffa5f39a773abfae8ed06a376d1949def6e0b7d92faf` |
| System 문자열 | 과거 F0/A/B와 Live 승인 Manifest 모두 `7540e67d11d342e9b9618204baa8d5868ebfd21a15716a8f41dc5fbcb35249a4` |
| Native Schema | 동일 `0a3b3262ad121eb467b2fe00a17d1dd8082a4887268005e8b70e4b14eb33e45f` |
| Batch System / Schema | 동일 `5d1fa52b4955d538579b9834eae356745b7f3dd700c2183d8486dc1ed7d0c812` / `6ac8f99e249ceefca8c63302ad1026f51415655d30029c67defc617bd04be924` |
| Policy/Few-shot | v2 / Production v3 short-fragment-boundary의 15개 예시 유지 |
| Request Model | `gpt-4o-mini` 동일. 과거 Pilot의 정확한 **응답 snapshot model**은 조기 HOLD 산출물에 미보존되어 재확인 불가 |
| Sampling / cap | provider defaults, 명시 sampling seed 없음; 입력 순서 seed55와 모델 seed는 별개. Native cap128, Batch128×실제 항목 수 |
| Timeout / Retry | 과거 독립 실험 timeout90s; #68 전송60s 및 Kafka poll lease 검사. SDK maxRetries3 유지. 이번 실제 HTTP200 1회, 출력20token으로 cap128 미도달 |

Schema는 Result/Category/Risk enum과 구조를 제한하지만 이 문장의 최소 Risk=HIGH를 강제하지 않는다. `ModerationResultValidator`는 필수값, SAFE=[]/LOW, FLAGGED category 비어 있음 등을 검사한다. `FLAGGED/[PROFANITY]/MEDIUM`은 구조적으로 유효하다. `ChatModerationService.persistCompleted`는 `response.result().riskLevel()`을 Entity에 그대로 전달한다. Native에는 Batch ID 변환도 없다. 현재 Evidence에는 Mapping/DB가 HIGH를 MEDIUM으로 바꿨다는 증거가 없다. 다만 과거 HTTP 원문과 SDK Entry가 보존되지 않은 한계는 남긴다.

#48의 Result/Category/Review Actionability 통과는 Risk Exact 통과가 아니다. HIGH→MEDIUM은 모두 `risk != LOW`여서 Actionability에는 일치할 수 있다. #48 README 총점이나 mismatch 목록 부재만으로 이 사례의 과거 HIGH 응답을 추정하지 않는다.

### 원인 후보와 개선 검토

- **확인**: Native에도 존재했던 semantic Risk 오류이며 이번 Batch 고유 회귀의 근거는 없다. GT HIGH와 Gate 보호 계약 미달은 그대로 남는다.
- **가설**: 간접·조건부 위해 표현을 MEDIUM의 “강한 적대적 표현”으로 적용했을 가능성. Prompt HIGH는 “협박, 위협 또는 위해를 가하겠다는 표현”이지만 예시는 직접적 “죽여버린다”다. 이 경계 적용 문제가 원인인지는 모델 내부 설명 없이 확정할 수 없다.
- **가설/한계**: 비결정적 모델 판단, 형식/동반 문맥 민감도. 과거 변동은 관측됐지만 이번 단일 Native 응답의 원인을 개별적으로 분리하지 못했다.
- **근거 약함**: 출력 잘림, Parser/enum 손상, Kafka/DB Risk 변조. 관측된 정상 Usage/구조/DB 흐름과 맞지 않지만 과거 누락된 원문을 새 코드 결과로 대체하지 않는다.
- **현재 가능한 개선**: Risk Exact/Critical 판정을 독립 보고하고 정상·HOLD 모두 응답 모델/SDK·HTTP Usage/수신·Commit 시각을 보존한다. GT나 Gate 완화 없이 오류를 드러낸다.
- **별도 승인 후 후보**: 정책 의미를 바꾸지 않는 위협 대 적대감 경계 설명 개선안을 Native/Batch 공통 Control과 비교. 해당 Case 원문·정답 암기, 자동 HIGH 보정, 좋은 결과까지 반복, 품질 HOLD 후 자동 Kafka/LLM 재실행은 하지 않는다. 과거 Q1의 FP 증가도 재검토해야 하므로 효과를 가정하지 않는다.

## 2. HOLD 관측 누락 보완

### 원인

Consumer interceptor는 최초 `receivedNs`를 메모리에 이미 저장했다. 그러나 Gate가 afterCommit에서 HOLD하면 `Issue68OpenAiKafkaHarness`는 정상 `Result/Sample` 조립 전에 예외를 던졌고, 기존 `finally` 부분 기록에는 수신 시각이 없었다. SDK observer의 Entry도 실행 helper가 정상 반환한 뒤에만 보고되므로 SDK 모델/Usage가 빠졌다. HTTP Usage와 DB afterCommit 정보 자체가 없었던 것은 아니다.

### 변경 (테스트 전용)

- `Issue68HttpBoundary`: SDK Entry 즉시 checkpoint + 보존 목록. Credential/HTTPS/예산/Usage 정산/Gate를 넓히지 않는다.
- `Issue68OpenAiKafkaHarness`: 정상·예외 모두 finally에서 최초 수신/flush/ACK/afterCommit/반환 시각, DB 분류·token·provider/model/version, logical/HTTP/SDK, Registry snapshot, UTC 시작/종료를 기록한다. DB 조회 불가 시 UNAVAILABLE로 남기며 예외 본문이나 원문을 저장하지 않는다.
- `Issue68OpenAiContext`: 기존 일반 Observation Handler(Timer)와 Spring AI `ChatModelMeterObservationHandler`(Token Counter)를 함께 연결한다. 초기 검증에서 후자가 빠져 Token Meter가 생성되지 않던 테스트 Context 차이를 발견했다. 동일 호출의 Timer/Usage 이중 계수는 Fake 2회=2 검증으로 방지한다.
- `Issue68MonitoringBridge`: Fake-only Registry 주입, `gen_ai.client.operation` histogram, Context 종료 전 마지막 exposition 동결 후 일시 HTTP 제공. Live용 exporter 권한은 추가하지 않는다.

`receivedNs`는 Consumer interceptor `onConsume`의 최초 관측이며 Broker 도착 시각이 아니다. 미수신/미완료의 0 timestamp로 지연을 계산하지 않는다. DB 완료는 TransactionSynchronization `afterCommit` 관측이며 `saveAndFlush` 시작/반환과 별도 필드다.

과거 `receivedNs=null`, SDK Entry/응답 모델 누락은 그대로 “미관측”이다. 기존 journal/UNKNOWN을 정산하거나 이전 실패를 PASS로 변경하지 않는다.

## 3. 기존 Prometheus/Grafana 검증

### 구성 차이와 분리 원칙

- 기존 Prometheus target는 **별도 호스트 앱 8080**의 Actuator를 15초마다 수집한다. ApplicationContextRunner Harness는 별도 JVM/Registry이며 웹 Actuator가 없다. 그냥 테스트를 실행하는 것만으로 기존 target에 들어가지 않는다.
- 로컬 monitoring에만 opt-in Fake job `issue68-harness-fake`(1초)를 추가했다. `build/issue68/monitoring/targets.json`이 테스트 중 `host.docker.internal:18068`을 등록하고 종료 시 `[]`로 해제한다. 고정 포트는 이 명시 실행에서만 바인딩한다. Production/실제 Live에는 붙이지 않는다.
- scrape 시 원래 Meter 이름에 `issue68_test_`를 붙여 **기존 Dashboard의 job 필터 없는 sum/rate/p95에 Fake 값이 섞이지 않게 한다**. bounded label `harness_scope=issue68_fake`만 추가한다. ID/원문/Key tag 없음.
- 기존 Dashboard UID/패널/PromQL/datasource 파일, 버전/volume은 변경하지 않았다. Prometheus만 `.env` 없이 `--env-file /dev/null`로 재생성했다. `--no-deps`이므로 Grafana는 재기동하지 않았다. unset MySQL env 경고는 이 명령의 미해석 항목이며 실행 중 Grafana 설정을 바꾸지 않았다.

### 실제 무과금 수집 결과

최종 로컬 검증 시간: **2026-10-22 02:21:16.887561~02:21:22.984246 UTC** (11:21:16~11:21:22 KST). SAFE-01 후 PROFANITY-14에 MEDIUM을 주입한 Fake 응답으로 HOLD를 재현했다. GT는 그대로이고 Fake 출력만 바꿨다. [원본 결과](critical-hold-fake-monitoring.json)는 `build/issue68/hold-monitoring-1792635683847.json`을 바이트 그대로 보존한 것이다. 앞선 02:14 Fake 실행 및 Grafana 조회도 동일한 Token/호출 수를 확인했지만 지연 수치는 합산하지 않는다.

| 항목 | 관측 |
|---|---:|
| 논리 / HTTP / SDK 성공 Entry | 2 / 2 / 2 |
| Input / Output / Total (Fake Usage) | 1,800 / 40 / 1,840 |
| Route | llm 2 |
| Final Status | SAFE 1 / FLAGGED 1 |
| SDK Usage = HTTP Usage | 2/2 동일 |
| DB afterCommit / committed next offset | 2 / partition0 next2 |
| Lag / DLT / unsafe commit | 0 / 0 / 0 |
| HTTP retries | 0 (HTTP count − logical count) |
| 계산상 비용 | $0.000294; Fake Usage에 공개 단가식을 적용한 값, 실제 청구 0 |
| p95 query | 약 3.5612s, 유한한 bucket query 값 확인 |

p95/Rate 검증을 위해 두 번째 Fake 응답을 잠시 대기시켜 첫 번째 Meter scrape를 확보했다. 이 지연은 **계측 fixture 인위 지연**이므로 Provider 성능이나 최적화 효과로 사용할 수 없다. 최종 최초 수신→Commit은 SAFE 약350.8ms, Critical 약3730.1ms였으며 동일하게 성능 비교값이 아니다. 이 두 건을 지연 분포의 대표값으로 일반화하지 않는다.

### Grafana 실제 화면 확인과 한계

- 사용자 로그인 후 기존 `Prometheus` datasource의 **Explore**에서 동일 시간대 input1800/output40/total1840, 논리2, 추정식0.000294를 실제 표시 확인했다. 기존 Dashboard에는 추가하지 않았고 기존 상단 llm19 / SAFE17 / FLAGGED4 / 비용0.002480이 유지됐다.
- 기존 `BobFull Local MySQL` datasource에서 `SELECT 1 AS connection_ok` 결과1 확인. 원문/식별자/사용자 행 조회 없음. 기존 DB는 host.docker.internal 고정 개발 DB이고, Harness DB는 Testcontainers 임의 포트의 `issue68_observation`이다. 기존 하단 MySQL 패널은 **테스트 DB를 자동 조회하지 않는다**. Batch request Usage를 개별 DB 행에 복사하지 않는 계약도 유지한다.
- 실행 중 Grafana의 provisioning bind mount 경로는 존재했지만 컨테이너 내 디렉터리 내용이 비어 있었다. 이미 저장된 Dashboard/datasource와 조회는 정상이다. 원인 및 Grafana 재생성 후 provisioning은 이번에 확인하지 않았다. Dashboard 변경/재기동으로 우회하지 않았다.
- 시간 picker는 조회 범위를 바꿀 뿐 누적 Counter를 실험별 delta로 바꾸거나 job을 격리하지 않는다. 여러 짧은 실험을 같은 series로 보내면 reset/staleness/5분 rate가 섞인다. 이번 prefix는 기존 앱 오염만 방지하며 Fake 재실행 간 자동 run 격리를 보장하지 않는다.
- 1 scrape만 있으면 rate/p95가 비거나 증가분이 사라질 수 있다. 이 검증은 첫 완료값과 다음 완료값을 각각 수집하고 Context 종료 뒤 frozen exposition까지 스크랩했다. 동결 구간은 실제 처리 시간과 다르며 긴 0 rate 꼬리가 생길 수 있다.
- 기존 Dashboard **그대로** 실제 Live Harness/격리 DB를 나타내는 연결은 아직 없다. 이번 명시 hook는 Fake 전용이며 Live에서는 거부한다. 필요하면 별도 승인으로 Live exporter/격리 datasource/패널 필터를 설계해야 한다. 기존 Dashboard에 Live 결과가 이미 수집된 것처럼 표현하지 않는다.
- Token/절감률/비용 비교의 원본은 조건별 Raw/SDK·HTTP Usage다. Grafana screenshot이나 Counter 총합만으로 절감률을 계산하지 않는다.
- 기존 Failure panel은 Provider/parse/validation/persistence 기술 실패 지표다. 구조적으로 유효한 모델 응답의 Critical Risk 오류는 그 Counter를 올리지 않는다. `failure=0`이어도 품질 Gate HOLD일 수 있으므로 Raw의 `v1v2`와 `executionHeld`를 함께 확인한다. Kafka retry Counter가 없을 때 `or vector(0)`은 표시 대체값이며, 실제 retry0은 HTTP/논리/전달 횟수 원본으로 별도 확인했다.
- Harness는 SDK customizer application interceptor 안에서 별도 guarded transport를 호출한다. Spring AI의 `okhttp.requests`는 SDK client application interceptor에 연결돼 있으므로, 이 무재시도 fixture의 2=2는 확인했지만 하위 transport follow-up까지 항상 동일하게 계수한다고 일반화하지 않는다. HTTP exchange별 실제 예약/Retry 판정의 기준은 `Issue68HttpBoundary.Attempt`다. Grafana의 HTTP−logical 차이0은 이 실행의 참고값이지 모든 장애에서 Kafka/SDK/transport Retry를 분리하는 만능 지표가 아니다.

## 4. 검증 명령과 결과

모든 Gradle 프로세스에서 `OPENAI_API_KEY`, Live approval/manifest 환경변수를 제거했다. 로컬 테스트는 `ISSUE68_LOCAL_MONITORING=true`일 때만 포트/실제 로컬 Prometheus에 접속한다. 기본 회귀에서는 이 한 건이 skip된다.

```sh
docker compose --env-file /dev/null -f docker-compose.monitoring.yml config --quiet
docker compose --env-file /dev/null -f docker-compose.monitoring.yml up -d --no-deps prometheus
docker exec bobfull-local-prometheus promtool check config /etc/prometheus/prometheus.yml
# 이하 Gradle 명령은 위 env unset 경계 적용
./gradlew :test --tests '*Issue68MonitoringBridgeTest' --tests '*Issue68HoldObservabilityTest' --no-daemon --console=plain
./gradlew :test --tests '*Issue68*' --tests '*SpringAiRuntimeMetricsIntegrationTest' --tests '*ChatModeration*Test' --tests '*SizeTimeBatchOwnerLoopSafetyTest' --no-daemon --console=plain
```

- compileTestJava PASS.
- 신규 4건 (exporter1, 정상/HOLD2, 실제 로컬 Prometheus1) 모두 PASS. 초기 Handler 연결 검증 실패는 Token Handler 누락/잘못된 first-matching 연결을 수정한 뒤 위 최종 실행으로 통과했다. 미통과 실행을 성공으로 표현하지 않는다.
- 관련 회귀: 42개 class / 286 tests / 4 skipped / failures0 / errors0, SUCCESS. 이후 최종 관측 필드·tag 검사 보강을 포함한 신규4건 재실행도 PASS.
- 최종 `./gradlew test build --rerun-tasks --no-daemon --console=plain`: **SUCCESS** (6m8s, 12 tasks executed). root1362 + Lambda11 = **1373 tests / 90 skipped / failures0 / errors0**. API Key 및 Live 승인 환경변수 unset, `clean` 미사용으로 build Raw 보존. 기본 전체 회귀의 90 skip에는 opt-in 로컬 monitoring1건이 포함되며 별도 로컬 실행에서 PASS했다. 실제 paid test는 실행하지 않았다.
- compose config / promtool PASS. 종료 후 Fake discovery `[]`, 기존 bobfull-backend target UP 확인.
- Dashboard JSON syntax 및 panel ID 11개 전부 unique, `git diff --check` PASS. 신규 테스트 파일의 whitespace도 별도 검사한다.

### 이번 변경 파일

| 파일 | 내용 |
|---|---|
| `Issue68HttpBoundary.java` | SDK 즉시 checkpoint와 Fake-only Registry hook |
| `Issue68OpenAiContext.java` | Registry hook / Token 전용 Observation Handler |
| `Issue68OpenAiKafkaHarness.java` | 정상·HOLD finally의 DB/시각/Usage/Meter/V1·V2 snapshot |
| `Issue68MonitoringBridge.java` | 신규 Fake Prometheus exporter와 종료 전 snapshot |
| `Issue68MonitoringBridgeTest.java` | exporter histogram 및 Context 종료 후 scrape |
| `Issue68HoldObservabilityTest.java` | 정상/HOLD Kafka·MySQL, SDK/HTTP, tag 및 로컬 수집 검증 |
| `docker-compose.monitoring.yml` | Prometheus에 build Fake discovery directory read-only mount |
| `ops/local-monitoring/prometheus/prometheus.yml` | opt-in Fake job / 수집 이름 격리 |
| 이 문서 / `critical-hold-fake-monitoring.json` | 새 분석 및 Fake 원본만 추가 |

테스트 Java는 모두 `src/test/java/com/bobfull/chat/infrastructure/{ai,kafka}` 아래다. 기존 `SizeTimeBatchOwnerLoop.java`의 미커밋 변경은 이번에 손대지 않았다.

### 불변 확인

작업 전 해시 522개와 대조: 누락0, 기존 파일 변경은 위 테스트3개/로컬 구성2개뿐이다. `src/main` 전체, 기존 #68 Evidence/private checkpoint, journal 및 Grafana 파일은 동일하다. 기존 #48/#55/#62/#64 Evidence의 tracked diff도 없다.

- private journal SHA-256: `80607e4746092d22067d61b818e69bd61f493e197f1a6d4d4cc58789c4fa6547` 불변. UNKNOWN $0.0192768, 정산 $0.00044625, 부담 $0.01972305, gross $0.077184의 기존 기록 유지. 장부를 새로 열어 정산/예약을 변경하지 않았다.
- Dashboard SHA-256: `a7bd9062f4225cc831d82c244565db7942e3d1f450bf8d656181a24684b5239c` 불변.
- 새 Fake Raw SHA-256: `83c17abf45eb86fad99515d5d7dc03ba69cf9adae25e48a3271b1f8a8e7635df` (build 원본과 동일).
- Branch/HEAD 유지, Commit/Push/PR/Issue 변경 없음. 전체 build 로그는 `/private/tmp/issue68-hold-observability-full-build.log`, 재실행 결과 XML은 root/Lambda `build/test-results/test`에 있다.

## 5. 재개 판단

품질 Gate V1/V2 HOLD 및 기존 예산 장부를 유지한다. 이번 작업으로 품질 문제가 해결된 것은 아니다. 같은 문장을 단순 재호출할 필요성은 과거 Native 6회 이력 때문에 낮다. 유료 진단이 필요하면 별도 Human 승인으로 일반적인 위협/적대감 경계의 합성 평가와 Native/Batch 동일 시점 Control, 최대 요청/예산/Gate를 먼저 확정한다. 이 작업의 Source 변경으로 기존 승인 Manifest를 재사용할 수 없으며 journal/claim 초기화로 재개하지 않는다. Pilot/본 Matrix 자동 재개 금지.
