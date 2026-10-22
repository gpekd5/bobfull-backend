# #68 — 비운영 Usage Benchmark 무과금 준비

## 범위와 기존 HOLD

이 문서는 2026-10-22 Human이 별도로 승인한 **테스트 구현 및 무과금 검증**을 기록한다.
기존 품질 평가의 Gate V1/V2 HOLD, Control/A1 Critical HIGH→MEDIUM 결과 및 기존 README의
조기 종료 판정은 소급 변경하지 않는다. 이번 Benchmark의 유료 실행은 아직 승인되지 않았다.
사용량 측정 완료와 품질 PASS, 운영 채택은 서로 다른 결과다. Production Batch는 기본 OFF다.

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`; #68 미커밋 테스트 코드 포함.
- 기존 Native/Poll Consumer, Spring AI Adapter, JPA, MySQL8.4, Kafka7.7.1 및 #67 테스트 Owner 재사용.
- 신규 `UsageBenchmarkPlan/Session/Execution`은 기존 품질 실행기와 분리한다.
- 테스트 외 Production, Prompt/Policy/Rule/Model, GT, Dashboard 및 datasource 변경 없음.
- 이번 실행은 loopback Fake HTTP뿐이다. API Key 읽기/사용, 실제 OpenAI, AWS, Commit/Push/PR 없음.

## 승인 대상 Matrix

| 단계 | 패턴 | 조건 | 관측 건수 | 신규 부담 한도 | 정상 논리 상한 | HTTP 상한 |
|---|---|---|---:|---:|---:|---:|
| BURST | 0ms 연속 발행 | Native, Poll, Time0/20/50/100 | 120 | $0.10 | 120 | 480 |
| REMAINDER | 250ms / 40ms / 5ms | 각 6조건 | 360 | $0.40 | 360 | 1,440 |
| 합계 | 4패턴 | 24 실행 단위 | 480 | $0.50 | 480 | 1,920 |

각 논리 요청당 실제 network exchange는 최대4회다. 숫자는 목표 재시도 횟수가 아니다.
REMAINDER는 BURST 완료와 별도 Human 승인 없이는 실행하지 않는다. 현재 Live 진입점은 BURST뿐이다.
Manifest의 `Unit.stage=PILOT`는 기존 Harness에서 diagnostic20을 선택하는 내부 타입이며,
중단된 과거 품질 Pilot의 승인이나 재개를 의미하지 않는다.

### 입력 및 비교 조건

- 승인된 역사적 diagnostic20, 원래 GT와 입력 순서 유지. 신규 블라인드 품질 평가가 아니다.
- 모든 조건에서 같은20건을 사용한다. 이20건은 Rule miss이며 실제 Rule 코드는 그대로 실행한다.
- partition3, concurrency1, max.poll.records10, autoCommit=false; Owner buffer 상한20.
- Native/Poll은 기존 Spring Listener, Size/Time은 테스트 단일 Consumer owner loop.
- Burst 조건 순서 고정: Time0 → Time20 → Time50 → Time100 → Native → Poll.
- 나머지 패턴도 고정 회전 순서다. 반복은 이번 Matrix에 포함하지 않는다.
- 발행 간격은 목표 deadline이다. 실제 발행/ACK/수신/flush/DB Commit/반환 시각과 poll·요청 구성을 별도 기록한다.
- 낮은 유입이라도 느린 실제 Provider에 의해 backlog와 후속 Batch가 생길 수 있다.
  Fake60ms에서의 Batch 크기를 실제 OpenAI의 예상 충전율로 확정하지 않는다.

### 요청 및 품질 계약

Production System Prompt/Few-shot15, 모델 `gpt-4o-mini`, Policy v2, 출력 cap128×실제 항목 수를 유지한다.
Native는 기존 단건 schema, Batch는 기존 추가 지시문/envelope/schema를 사용한다. 두 요청의 schema는
동일하지 않으며 형식 차이도 사용량·품질에 영향을 줄 수 있다. A1, Checklist, Q1은 사용하지 않는다.
Manifest에 두 system/schema hash와 테스트·Production source hash를 각각 포함한다.

FP/FN, Category/Risk, Critical, HIGH 하향, Review 신호 오류는 원래 evaluator로 계산하여 그대로 남긴다.
**이 독립적인 비운영 Benchmark에 한해서만** 의미상 오류를 기록하며 다음 측정을 진행한다.
기존 품질 실행기의 중단 동작을 완화하지 않는다. HTTP/Usage/Mapping/Validation/DB/Offset/Lag/예산/
승인 오류는 다음 network exchange 전에 차단한다. 중단된 단계의 남은 수량을 채우려고 재실행하지 않는다.

## 장부와 전송 안전장치

무과금 테스트 실행기는 기존 `.issue68-live/budget.jsonl`을 열거나 변경하지 않는다.
테스트는 공개된 이벤트로 임시 디렉터리에 같은 과거 prefix를 재현한다.

| 과거 항목 | 보존 값 |
|---|---:|
| 정산액(cache 미반영 보수적 비용) | $0.00060495 |
| UNKNOWN 예약 | $0.01927680 |
| 현재 부담(정산+UNKNOWN) | $0.01988175 |
| gross 예약 이력 | $0.09646080 |
| 과거 HTTP attempt | 5 |

고정 장부 SHA-256: `35924fde9b6de4437b5294b9e23daf48322c51daa012c509f08aa15c893e01d0`.
기존 UNKNOWN을 환급하거나 과거 A1/Pilot을 END/PASS로 바꾸지 않고 새 승인 scope를 append하는 계약이다.

- network interceptor에서 목적지/요청/논리별 HTTP 상한 재검사 → poll lease 검사 → 원자적 선예약/fsync → 전송.
- input128,000 token 및 실제 출력 cap으로 보수 예약. 요청 추정 tokenizer 값으로 예약을 줄이지 않는다.
- HTTPS443 `api.openai.com/v1/chat/completions` POST만 허용; proxy/redirect/비승인 옵션 차단.
- SDK Retry와 하위 HTTP 재전송도 같은 경계를 통과한다. 상태·Usage가 불명인 응답은 예약을 유지하고 Retry까지 차단.
- HTTP Usage와 SDK Usage 불일치는 환급분을 `DISPUTED_KEEP`로 복원한다. 이중 정산은 거부한다.
- 단계 $0.10/$0.40, Benchmark 누적 $0.50, 과거 포함 전역 $1을 동시에 검사한다.
- 단일 journal FileLock, 별도 stage claim, source/approval hash 검사, 고정 checkpoint 디렉터리, CREATE_NEW/fsync.
- 재시작 시 pending/UNKNOWN을 부담에 포함한 audit만 허용한다. 새 journal/손상/링크/중복 claim으로 초기화할 수 없다.
- 승인 파일 변조, commit 실패, 부정확한 Usage 또는 예산 부족 시 HOLD 및 부분 결과를 보존한다.
- 로컬 경계가 이 Harness의 보수적 부담을 제한한다. 계정의 다른 클라이언트 청구액을 통제하는 장치는 아니다.

## 예상 비용 — 실제 Usage가 아님

기존 무과금 estimator의 Native20 및 완전충전 Batch10 두 형상을 재사용했다.
공개 gpt-4o-mini 단가는 input $0.15 / cached input $0.075 / output $0.60 per1M이다.
[모델/단가](https://developers.openai.com/api/docs/models/gpt-4o-mini).
아래는 cache 미반영, 출력 cap 전량 사용, 정상 요청 기준의 **추정 형상 범위**다.
실제 usage나 보장 상한으로 해석하지 않는다.

| 단계 | 논리 요청 형상 범위 | 추정 Input | 출력 cap 합계 | 추정 비용 |
|---|---:|---:|---:|---:|
| Burst120 | 30~120 | 35,364~124,914 | 15,360 | $0.01452060~$0.02795310 |
| 나머지360 | 90~360 | 106,092~374,742 | 46,080 | $0.04356180~$0.08385930 |
| 전체480 | 120~480 | 141,456~499,656 | 61,440 | $0.05808240~$0.11181240 |

최소치는 non-Native의 완전충전 형상, 최대치는 전부 underfill Native가 되는 형상이다.
각 형상의4회 전송 추정치는 전체 $0.23232960~$0.44724960이나 tokenizer 추정은 실제 입력 상한이 아니다.
실제 context 상한을 모든 전송에 적용한 이론적 비용은 $37.011456까지로 $1을 넘는다.
따라서 HTTP1,920회를 모두 보낸다는 약속이 아니라 **예산이 먼저 부족하면 전송을 멈추는 계약**이다.
단계별 예상 비용이 한도 아래여도 다음 보수 예약 약$0.01928~$0.01997의 여유가 없으면 조기 중단된다.
Usage 불명, 실패, 예상보다 큰 응답/입력 또는 Retry는 120건 미완료 원인이 될 수 있다.

## 관측과 기존 Grafana

요청 단위 logical/HTTP/SDK usage, cached 값, cache 미반영·반영 비용을 기록한다.
cached 필드가 없으면 0으로 채우지 않고 할인 비용을 UNKNOWN으로 남긴다.
Batch 전체 Usage를 개별 DB 메시지 row에 복제하거나 임의 배분하지 않는다.

각 조건의 UTC 시작/종료, Batch 크기 분포/충전율, Native/Batch 논리 요청, poll, Lag, DLT,
수신→DB **Commit** 및 발행 ACK→DB Commit p50/p95, Owner flush의 buffer wait/maxBuffered를 기록한다.
논리 Provider 경과 시간에는 Adapter/SDK와 계측·정산 overhead도 포함되며 순수 서버 연산 시간은 아니다.
Owner와 기존 Listener의 구조적 차이도 별도 해석한다.

기존 Dashboard 패널/PromQL/UID/datasource를 변경하지 않는다. 각 Fake 조건은 fresh registry를 사용하고
Context 종료 전에 scrape를 동결해 exporter에서 유지한다. 추가 Fake 검증에서는 조건별 새 tmpfs Prometheus가
exporter 하나만 수집하며 기존 앱8080/named volume을 연결하지 않는다.
기존 LLM Call Count/Token Usage/Estimated Cost/p95 패널의 **원본 PromQL**을 이 실제 Prometheus에 조회한다.
첫 count=1 scrape 이후 다음 완료를 허용해 rate/p95 증분도 남긴다. 이 의도적 대기는 성능 비교에 사용하지 않는다.

이는 Dashboard 원본 쿼리에 대한 새 수집 검증이지 이번에 새 Grafana 화면/캡처를 확인했다는 뜻은 아니다.
이미 완료한 기존 화면 연결 검증은 [기존 절차](../../../../../ops/local-monitoring/issue68-dashboard/README.md)를 따른다.
기존 MySQL datasource는 변경하지 않으며 Testcontainers DB 결과를 표시하지 않는다.
정확한 Token·지연 비교는 Dashboard 반올림/counter/rate가 아니라 조건별 원본 결과로 계산한다.

## 실행 및 제출 상태

무과금 명령은 `.env`를 source하지 않고 Key/Live 승인 변수를 제거한다.

```sh
ruby -e 'ENV.keys.grep(/\A(?:OPENAI_API_KEY|ISSUE68_.*)\z/).each { |k| ENV.delete(k) }; exec "./gradlew", ":test", "--tests", "*Issue68*", "--no-daemon", "--console=plain"'
ruby -e 'ENV.keys.grep(/\A(?:OPENAI_API_KEY|ISSUE68_.*)\z/).each { |k| ENV.delete(k) }; exec "./gradlew", "build", "--rerun-tasks", "--no-daemon", "--console=plain"'
```

검증 결과와 동결 승인 hash는 실행 완료 후 [검증 기록](usage-benchmark-free-results.json) 및
[Burst 승인 제안](usage-benchmark-burst-approval-proposal.json)에 기록한다.
현재 코드 작성/무과금 검증은 유료 승인이 아니다. 후속 Human이 정확한 Manifest SHA를 승인하기 전
`Issue68UsageBenchmarkLiveTest`를 실행하지 않는다. O0/N1/기존 Pilot도 자동 재개하지 않는다.

## 최종 무과금 검증 결과

| 검증 | 실제 결과 |
|---|---|
| 신규 안전성 / Kafka·MySQL / Prometheus | 20 + 13 = **33 PASS** |
| 신규 유료 진입점 | 승인 부재로 **1 SKIP**, 실제 호출0 |
| #68 관련 회귀 | 291건 / skipped7 / failure0 / error0 |
| 전체 root | 1,474건 / skipped93 / failure0 / error0 |
| 전체 Lambda | 11건 / skipped0 / failure0 / error0 |
| 전체 Build | **SUCCESS**, `--rerun-tasks`, 12 tasks executed, 8m56s |
| Fake 전체 Matrix | 24실행 / 480건, 모든 실행 DB완료20 / Lag0 / DLT0 / unsafe commit0 |
| 별도 Fake 모니터링 | 6조건 각각 새 Prometheus에서 기존4패널 원본 PromQL 조회, p95 유한 값 |
| 기존 앱 Counter | localhost9090 원래 call count19 유지 확인; 신규 Fake와 합산되지 않음 |
| JSON / 로컬 문서 링크 / Compose / diff-check | PASS; `.env` 미사용 Compose는 MYSQL placeholder 경고만 존재 |
| 기존 파일 보존 | 기존 Evidence·private 기록·Production·Grafana 등 497개 SHA 동일 |

Fake 전체 Matrix는 `SEMANTIC_SAFE`를 의도적으로 반환해 각 실행에 FN9건과 Critical 실패를 만든다.
이 실패를 삭제하거나 품질 PASS로 바꾸지 않고 480건 측정 완료가 가능한지 검증한 것이다.
실제 모델의 새로운 FN 관측치나 Token 절감률로 해석하지 않는다.
정상 계측 외에 Usage/SDK Retry/Mapping/Validation/Parse/잘림, Owner Commit, Native DB/Commit,
예산 부족, 장부 손상·재시작·중복 실행·변조 승인 및 이중 정산의 차단도 검증했다.

원본 신규 Fake 결과는 `build/issue68/usage-benchmark-fake-1792653155285.json`에 별도로 보존한다.
SHA-256: `d5d08fe52f9b344163d4207c6ab17bd3633ef52dba384bc4adc539e27dea4fd6`.
이 파일은 생성된 로컬 원본이므로 Git 제출 대상에 넣지 않았으며, 연결된 검증 JSON은 비식별 요약이다.
과거 원본이나 기존 journal을 새 코드의 성공 결과로 변경하지 않았다.

### 유료 Burst120 승인 대상

- 승인 ID: `issue68-usage-benchmark-burst-v1`
- Manifest SHA-256: `d61581f2947bda099a4277d0e90745c4fe8922032f61e3e1fa706ba27d68a0a9`
- 범위: Burst 6조건×20건, 논리 최대120 / HTTP 최대480 / 논리당4회.
- 신규 Burst 부담 $0.10, Benchmark 누적 $0.50, 기존 UNKNOWN 포함 전역 $1.00.
- 기존 품질 HOLD 유지, 비운영 사용량 측정에서만 의미상 오류 기록 후 계속.
- 기술 오류/UNKNOWN/예산 부족 시 후속 전송·자동 재개 금지, 부분 결과 보존.
- 다음360건, 품질 재평가, 운영 활성화는 승인 대상에 포함하지 않는다.

실제 HTTPS 인증·계정 모델 접근·Live Batch 구성·Usage/지연은 이번 무과금 검증으로 확인하지 않았다.
통신/예약/정산의 실제 결과는 후속 승인된 최초 유료 단계에서 확인해야 한다.
현재는 **유료 Burst 실행 승인 대기**이며 Commit/Push/PR/CI/Issue Close는 수행하지 않았다.
