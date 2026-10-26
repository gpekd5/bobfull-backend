# 기존 Dashboard 패널의 Fake 호출 표시 검증

## 측정 계약

- Primary: PR #54의 기존 `BobFull AI Moderation Observability` **실제 패널**에서 새 Fake 호출 수/Token/비용/p95 확인. Explore 또는 Prometheus API 성공만으로 PASS하지 않는다.
- Secondary: 실제 실행 중 Dashboard export와 저장소 원본의 query/datasource/UID/배치 비교, scrape 및 Context 종료 경계 확인.
- Guardrail: Production/Prompt/Policy/Rule/Model/GT/Gate/기존 Raw/journal 불변. 실제 OpenAI 및 Key 사용 없음. 기존 앱 Counter와 Fake 값을 합산하지 않음. 기존 패널과 디자인 변경 없음.
- Branch `feature/68-openai-poll-batch-verification`, HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807` + 보존 중인 미커밋 작업. Commit/Push/PR/Issue 변경 없음.

## Before: 이름 불일치와 불충분한 완료 기준

[앞선 관측 검증](critical-hold-observability.md)은 Fake를 기존 앱과 섞지 않기 위해 기존 Prometheus에서 `issue68_test_*`로 수집하고 Explore에서 확인했다. 기존 Dashboard는 prefix 없는 `gen_ai_*`/`bobfull_*`를 사용하므로 Fake가 기존 패널에 표시되지 않았다. 이 사실을 숨기거나 과거 결과를 패널 검증 PASS로 소급 변경하지 않는다.

기존 Dashboard 실제 PromQL:

| 패널 | Query |
|---|---|
| LLM Call Count | `sum(gen_ai_client_operation_seconds_count)` |
| Token Usage | `sum by (gen_ai_token_type) (gen_ai_client_token_usage_total)` |
| Estimated Cost | input×0.15 + output×0.60, 1M으로 나눔; `gen_ai_system="openai"`, `gen_ai_request_model="gpt-4o-mini"` 필터 |
| LLM p95 Latency | `histogram_quantile(0.95, sum by (le) (rate(gen_ai_client_operation_seconds_bucket[5m])))` |

네 패널 모두 datasource UID `prometheus`. Fake 원본 exporter는 이 이름과 model/token-type 라벨을 이미 제공한다. 이름의 불일치는 exporter가 아니라 앞선 Prometheus scrape relabel에서 발생했다.

## After: 같은 Dashboard/UID, 격리된 데이터 소스 연결

별도 테스트 Prometheus를 **기존과 같은 image v3.14.0**으로 실행했다. 일시 tmpfs TSDB, 한 개 target `host.docker.internal:18068`, 1초 scrape다. `harness_scope=issue68_fake`만 수집하고 8080 앱 job은 없다. 이름 prefix 변경도 없다.

기존 Grafana의 datasource **UID/Name는 그대로** 두고, 검증 동안 URL만 `http://prometheus:9090` → `http://host.docker.internal:19068`로 연결했다. 양쪽 모두 Save & test 성공을 UI에서 확인했다. 실제 패널 표시를 확인한 뒤 원래 `http://prometheus:9090`으로 복원했다. 기존 Prometheus named volume, 데이터, Grafana Dashboard JSON/패널/디자인/provisioning 파일은 변경하지 않았다. Grafana 재기동이나 renderer plugin 설치도 하지 않았다.

따라서 기존 앱19건과 Fake2건을 합친21건이 아니라 **2건만** 보였다. 원복 후19건은 정상이며, 검증 결과가 사라지거나 연결이 실패했다는 의미가 아니다.

## 실제 실행 및 패널 결과

- 실제 새 실행: Fake HTTP + Testcontainers Kafka/MySQL, Native2건. 실제 Spring AI Adapter/Consumer/JPA 경로 사용.
- 실행 UTC: `2026-10-22T03:23:25.016741Z` ~ `03:23:28.426413Z` (KST12:23:25~28, Harness Context 포함).
- Context 종료 후 frozen exporter 관측 시작: `03:23:29.305584Z`, 180초 유지. 추가 Provider 호출 없음.
- Dashboard 고정 시간 범위: **2026-10-22 12:23:10~12:24:30 KST**.
- [새 Fake Raw](dashboard-panel-fake-results.json): build 원본 `dashboard-monitoring-1792639409296.json`을 그대로 보존. 패널 원본 PromQL로 조회한 결과를 포함한다.
- [실행 중 Dashboard export](dashboard-runtime-export.json): Grafana Export as code로 내려받은 실제 JSON. 원본 메시지/개인정보/Key 없음.

| 기존 Dashboard 패널 | 실제 화면 표시 | Raw |
|---|---|---|
| LLM Call Count | **2** | 2 |
| Estimated Cost (USD) | **$0.000294** | 0.000294 |
| Token Usage input | **2 K** (기존 반올림) | 1,800 |
| Token Usage output | **40** | 40 |
| Token Usage total | **2 K** (기존 반올림) | 1,840 |
| LLM p95 Latency | **532.397 ms** | 0.53239698675초 |
| Moderation Route | llm2 | 2 |
| Final Status | SAFE1 / FLAGGED1 | 각1 |
| Failure / Kafka Retry | 0 / 0 | 실제 전달/HTTP 원본과 함께 확인 |

위 표의 화면 값은 **기존 Dashboard UI에서 직접 확인**했다. Explore 표를 Dashboard 결과로 대체하지 않았다. Dataset 합성 두 건 및 Fake 고정 Usage이므로 실제 모델 Token 절감이나 비용/품질 개선 결과가 아니다. 비용은 Fake token에 기존 패널의 단가식을 적용한 수치로 실제 유료 비용0이다. p95는 첫 scrape 확보를 위한 fixture 대기와 histogram 보간이 포함돼 실제 OpenAI/운영 성능으로 사용하지 않는다.

전체 Dashboard 캡처는 채팅에 표시했다. Grafana Export as image는 renderer 미설치로 불가능했고 새 plugin을 설치하지 않았다. **Human의 후속 요청으로 이미지 파일 저장은 생략**했다. 이미지 파일이 저장된 것처럼 경로를 만들거나 캡처를 재구성하지 않는다.

## Dashboard 불변 검증

- 저장소 JSON SHA-256: `a7bd9062f4225cc831d82c244565db7942e3d1f450bf8d656181a24684b5239c`.
- UID `bobfull-ai-moderation`, panel ID11개 unique.
- 런타임 export의 모든 panel ID에 대해 `title/type/gridPos/targets/datasource`가 저장소 원본과 동일하다. 네 핵심 패널의 실제 PromQL과 UID도 직접 대조했다.
- 런타임 export는 Grafana가 panel 배열 순서를 grid 순서로 정렬하고 mappings/custom/threshold 등 기본값을 보충하므로 저장소 JSON과 byte 동일하지 않다. 이것을 패널 변경으로 숨기거나 저장소 파일에 덮어쓰지 않는다.
- 새 Fake Raw SHA-256: `e10bfc92069663537874d3194bec58259b2a8dbb108217c61ab7aaf4dfabcfd7`.
- 실행 중 Dashboard export SHA-256: `eb7fa1a975d46816ab7b763f96f6f7873e74bf1d8340b9bb52ccc9666081048f`.
- datasource **URL만 일시 전환 후 원복**. Name/UID/authentication/설정 파일은 그대로다. 이 전환 동안 같은 UID를 사용하는 다른 로컬 패널에도 영향을 줄 수 있으므로 동시에 다른 측정을 하지 않는다.

## Scrape/오염/Context 종료 검증

1. 기존 앱은 기존 Prometheus의 8080 target에서 계속 수집했다. Fake namespace 및 별도 TSDB 때문에 원래 Counter는 **19 유지**했다.
2. 격리 Prometheus는 Fake target 하나만 UP이며 기존 앱 target이 없다. 기존 family 중 `harness_scope!=issue68_fake` 값이 없음을 자동 검증했다.
3. 첫 완료 count1의 scrape를 확보한 뒤 두 번째 Fake 응답을 완료시켜 count2/Token/p95를 수집했다. 한 번만 scrape된 짧은 Context의 rate 누락을 피한다.
4. Context 종료 전 Registry를 freeze하고 exporter를 UI 확인 동안 유지했다. shutdown 때문에 정상·HOLD 결과가 사라지지 않았다.
5. 종료 후에는 절대 시간 범위를 사용한다. 현재 시각 instant query는 target 종료 뒤 stale/No data가 될 수 있다. Counter는 시간 picker만으로 실행별 delta가 되지 않고, 5분 rate/p95에는 window/step/interpolation 영향이 있다.
6. 새 실행 전에 격리 TSDB의 기존 operation count가 존재하면 테스트가 거부한다. 기존 journal이나 앱 TSDB를 초기화해 이 조건을 우회하지 않는다.

## MySQL 패널의 별도 제한

기존 하단 두 패널의 datasource는 `bobfull-local-mysql`, 개발 DB `host.docker.internal:3307`이다. 실제 쿼리는 `chat_moderation.analyzed_at` 시간 필터와 `provider='OpenAI'`, non-null prompt/total token을 사용한다. **Testcontainers의 임의 포트 `issue68_observation` DB를 조회하지 않는다.** datasource와 SQL을 바꾸거나 Fake 행을 개발 DB에 복제하지 않았다.

따라서 해당 시간대의 하단 패널이 빈 상태인 것은 정상적인 격리 결과이며 테스트 DB 조회 성공으로 주장하지 않는다. 기존 MySQL 연결의 이전 `SELECT 1` 확인과 테스트 DB의 실제 JPA/Commit 확인은 별도 근거다. 이번 네 Prometheus 패널 검증은 MySQL per-call 패널 연결까지 완료했다는 의미가 아니다.

## 품질/예산 불변

동결 GT는 그대로이며 Fake PROFANITY-14 응답만 MEDIUM으로 주입했다. V1 `GT_HIGH_DOWNGRADE`, V2 `CRITICAL_RISK_DOWNGRADE`, `executionHeld=true`가 Raw에 남는다. DB Commit2/lag0/DLT0/unsafeCommit0은 이 Fake fixture 결과다. 기술 Failure Counter0은 품질 PASS를 의미하지 않는다.

Live journal/UNKNOWN 및 기존 Raw는 바꾸지 않았다. 이 무과금 테스트는 임시 Fake budget만 사용한다. 실제 OpenAI/Key 사용, Production 활성화, Pilot 재개/품질 Gate 완화, Commit/Push/PR 없음.

## 재현 및 검증 결과

[전용 실행 절차](../../../../../ops/local-monitoring/issue68-dashboard/README.md)에 URL 전환/복원, opt-in 테스트와 절대 시간 범위 선택 방법을 기록했다.

- compileTestJava 및 신규 계약/Fake 기본 검증: 7 tests /2 skipped /failures0/errors0, SUCCESS.
- 실제 Dashboard 전용 opt-in: 1 test /0 skipped/failures/errors, SUCCESS (3m17s, 180초 exporter 보존 포함).
- 격리 compose config / promtool: PASS. Grafana container에서 격리 Prometheus 준비 endpoint 접근 및 Save & test: PASS.
- 전체 회귀: 위 재현 명령의 Key/Live 승인 환경변수 제거를 동일하게 적용한 `./gradlew test build --rerun-tasks --no-daemon --console=plain`. **BUILD SUCCESSFUL, 6m41s, 12 tasks executed**.
- root XML: 1,365 tests /91 skipped /failures0/errors0. Lambda XML: 11 tests /0 skipped /failures0/errors0. **합계 1,376 tests /91 skipped /failures0/errors0**. 기존 Gradle 계약대로 `kafka-evidence`는 기본 test 대상에서 제외한다. 위 opt-in 실제 패널 검증 1건은 별도 실행 결과다.
- 시작 시 보존 대상 805개 파일을 SHA-256으로 대조했다. 기존 파일 중 이번에 변경된 것은 `Issue68HoldObservabilityTest.java`뿐이며 누락0. Production, 이전 Evidence/Raw, 기존 로컬 monitoring 설정, journal은 시작 시점과 동일하다.
- Live journal SHA-256 `80607e4746092d22067d61b818e69bd61f493e197f1a6d4d4cc58789c4fa6547` 보존. UNKNOWN `$0.0192768`을 해제하거나 재정산하지 않았다.
- JSON syntax, 11개 panel ID uniqueness, runtime/core panel 동등성, `git diff --check`: PASS.
- Raw 보존 및 datasource 원복 후 **격리 테스트 Prometheus 컨테이너와 일시 TSDB만 종료/제거**했다. 원래 Prometheus의 LLM count는 원복 후에도19였다. 기존 앱 TSDB/volume은 제거하지 않았다. 다시 Fake 패널을 확인하려면 위 절차로 새 Fake 실행이 필요하다.

## 이번 보완 파일

- `Issue68HoldObservabilityTest.java`: 기존 패널 PromQL/격리 TSDB 검증 및 opt-in UI 관측 시간 확보.
- `Issue68DashboardContractTest.java`: 기존 Dashboard 원본 불변 및 수집 격리 계약 2건.
- `ops/local-monitoring/issue68-dashboard/{docker-compose.yml,prometheus.yml,README.md}`: 테스트 전용 수집기와 재현/원복 절차.
- 이 문서, 새 Fake Raw 및 실제 runtime Dashboard export. 과거 결과 파일은 덮어쓰지 않았다.
