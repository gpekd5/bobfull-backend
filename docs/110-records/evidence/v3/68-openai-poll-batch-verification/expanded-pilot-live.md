# Issue #68 — 승인된 6조건 Live Pilot: Native Critical Gate HOLD

## 결론과 범위

Human이 [승인 Manifest](expanded-pilot-approval-manifest.json)의 Pilot120만 명시 승인하여
실제 OpenAI 실행을 **1회 시작**했다. Native에서 첫 실제 요청의 `HOLDOUT-PROFANITY-14`가
GT HIGH → MEDIUM으로 반환되어 승인된 Critical 최소 위험도 Gate V2에 따라 즉시 중단했다.

**120건 완료가 아니다. Native1건만 완료했고, Native19건 및 나머지5조건100건은 평가하지 않았다.**
본 비교1,920건/반복960건/추가 유료 재실행은 하지 않는다. 과거 Gate V1 `BATCH_GATE_HOLD`를 보존하며
이번 실행은 `PILOT_HOLD_HUMAN_REAPPROVAL_REQUIRED`다. Batch 고유 회귀나 최종 방식 채택을 판단할 자료는 아니다.

## 실행 계약과 시각

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`.
- 브랜치: `feature/68-openai-poll-batch-verification`, 기존 #68 전체 미커밋 상태 유지.
- 승인 ID: `issue68-six-condition-pilot120-gate-v2-v1`.
- Manifest SHA256: `3540e688973ad207179e1e549a05ce816ca87ab5750e46aac20e827bae12b5c1`.
- 실행 전/후 승인된 테스트42개 및 Production15개 source hash 일치. Source/GT/metadata/Model/Prompt/Policy/Rule 변경 없음.
- 신규 Pilot 부담 한도 $0.10, 기존 부담 포함 전역 한도 $1.00. 동일 고정 journal을 append-only로 사용했다.
- Model 요청: `gpt-4o-mini`, 기존 Prompt/Few-shot15/Policy v2/Schema/Native output cap128 유지.
- 과거 실험에 사용한 합성 진단20건을 동일 순서로 발행했다. 신규 블라인드 평가나 실제 사용자 데이터가 아니다.
- Kafka7.7.1/MySQL8.4 Testcontainers, 실제 Native Consumer/Adapter/JPA, partition3/concurrency1/max.poll.records10.
- 발행 목표는 Burst0ms다. Partition 간 전역 처리 순서를 강제하지 않으며, 이번 poll의 첫 처리 대상은 PROFANITY-14였다.
- API Key는 `.env`의 `OPENAI_API_KEY` 하나만 읽어 Gradle 자식 환경에 일시 전달했다.
  `.env`를 source하거나 다른 항목을 주입하지 않았고 Key 값은 명령 인수/콘솔/Evidence에 저장하지 않았다.
- UTC 시작: **2026-10-22T01:54:02.777267Z** / 종료: **2026-10-22T01:54:29.713014Z**.
- KST: **2026-10-22 10:54:02.777267 ~ 10:54:29.713014**.
  위 전체 시간은 Gradle 컴파일과 컨테이너 기동을 포함하며 Provider latency와 다르다.

필터는 `:test --tests '*Issue68ExpandedPilotLiveTest' --rerun-tasks --no-daemon --console=plain`만 사용했다.
별도 Pilot 승인 ID/Manifest 환경변수만 활성화하고 기존 Smoke/O0 승인 변수는 제거했다.
한 번 중단된 claim/journal을 초기화하거나 재실행하지 않았다.

## 조건별 실제 비교표

| 조건 | 완료/계획 | 논리/HTTP | Input / Cached / Output / Total | 신규 추정 비용 | HTTP 응답시간 | 결과 |
|---|---:|---:|---|---:|---:|---|
| Native | 1/20 | 1/1 | 821 / 0 / 20 / 841 | $0.00013515 | 2,069.020ms | Critical Risk 하향, HOLD |
| Poll-Batch | 0/20 | 0/0 | 미측정 | 미측정 | 미측정 | 앞선 Gate HOLD로 미실행 |
| Size/Time0ms | 0/20 | 0/0 | 미측정 | 미측정 | 미측정 | 미실행 |
| Size/Time20ms | 0/20 | 0/0 | 미측정 | 미측정 | 미측정 | 미실행 |
| Size/Time50ms | 0/20 | 0/0 | 미측정 | 미측정 | 미측정 | 미실행 |
| Size/Time100ms | 0/20 | 0/0 | 미측정 | 미측정 | 미측정 | 미실행 |

실제 HTTP는 글로벌 attempt4/해당 실행 logical1이며 status200, Retry0, `Content-Encoding=gzip`,
정산 상태 `SETTLED`다. 이번 응답에서 gzip은 관측됐으나 과거 UNKNOWN의 Content-Encoding이나
원인을 소급 확정하지 않는다. 기존 UNKNOWN은 유지한다.

비용은 HTTP Usage와 공식 [gpt-4o-mini 단가](https://developers.openai.com/api/docs/models/gpt-4o-mini)
input $0.15 / cached $0.075 / output $0.60 per1M의 **Estimated Cost**다.
이번 cached input은0이므로 Cache 할인 적용/미적용 금액은 모두 $0.00013515다.
실제 청구서 금액이 아니며 동기 Micro-batching에 OpenAI Batch API 할인을 적용하지 않는다.

## 품질: PROFANITY-14

| 항목 | 동결 기준 | 실제 관측 |
|---|---|---|
| 합성 원문 | 한 번만 더 그러면 가만 안 둔다 | 같은 승인 Dataset 입력 |
| Result | FLAGGED | FLAGGED |
| Category | PROFANITY | PROFANITY |
| Risk | HIGH | MEDIUM |
| Metadata | CRITICAL / 최소 HIGH | 최소 Risk 미달 |
| V1 | GT HIGH 하향 차단 | `GT_HIGH_DOWNGRADE` |
| V2 | Critical Risk 하향 차단 | `CRITICAL_RISK_DOWNGRADE` |
| Review Target | 대상 | 신호 유지 |

Human이 기존에 PROFANITY-14/15를 엄격 보호로 지정했고, metadata는
`Human 엄격 보호: 조건부 보복 또는 거주지 추적·방문; 기존 HIGH 유지`를 근거로 한다.
PROFANITY-16의 문맥 의존 조건부 허용과 다르다. FLAGGED/Category/검토 신호가 유지되어도
**Critical의 HIGH→MEDIUM은 비차단 예외가 아니다.** GT나 metadata를 모델에 맞춰 바꾸지 않았다.

- 완료1건 기준 TP1 / TN0 / FP0 / FN0, Result1/1, Category1/1, Risk0/1.
- HIGH 하향1 / Critical 보호 실패1 / Review Target 누락0 / 관측된 cross-field 위반0.
- 이 값은 완료한1건만의 관측이며 Native20건 전체 정확도/P/R/F1이나 품질 유지 결과로 일반화하지 않는다.
- 나머지19건은 `NOT_EVALUATED/DB 미완료`이며 SAFE/정답/모델 FN으로 대체하지 않는다.
- Batch를 호출하지 않았으므로 Native 대비 Batch 신규 FP/FN/Category/Risk 회귀는 **평가 불가**다.
- 현재 관측은 Native에서 발생한 오류이며 Batch 고유 회귀로 표현하지 않는다.

## Kafka / DB / 지연

- Native20건의 발행 ACK가 보존됐다. 실제 첫 poll에는10건이 있었지만 Provider 처리 완료는1건이다.
- PROFANITY-14의 실제 JPA afterCommit1건 관측. Commit 후 저장 메서드 반환 시각도 기록됐다.
- 해당 원본 record: partition2/offset0; Kafka committed next offset은 partition2/1이다.
- 다른 미완료 record를 넘은 unsafe commit0, 최종 snapshot Lag19, DLT0.
- 완료 Case의 onConsume delivery1, HTTP Retry0. Retry/Recovery-only 장애 복구를 이번 정상 HTTP1건으로 입증하지 않는다.
- Lag19는 승인된 중단 후 남은 미완료 작업이지 적체 해소 성공이 아니다.
- 발행 ACK→DB Commit: **2,541.361167ms (N=1)**.
- Kafka 수신→DB Commit: 조기 HOLD의 partial snapshot에 `receivedNs`가 없어 **산출 불가**.
  정상 `Result/Sample` 생성 전에 종료됐으며 값을 추정하거나 HTTP 시간으로 대체하지 않는다.
- p50/p95는20건 전체 또는 조건 간 비교가 불가능하므로 보고하지 않는다.
- 테스트 종료 시 컨테이너는 정리됐다. 영속 실행 DB가 남아 있다고 주장하지 않고 부분 snapshot을 보존한다.

## 예산 장부

| 구분 | 실행 전 | 이번 변화 | 실행 후 |
|---|---:|---:|---:|
| UNKNOWN | $0.0192768 | $0 | $0.0192768 |
| 정산 | $0.0003111 | $0.00013515 | $0.00044625 |
| 진행 중 예약 | $0 | $0 | $0 |
| 부담 합계 | $0.0195879 | $0.00013515 | $0.01972305 |
| Gross 선예약 이력 | $0.0579072 | $0.0192768 | $0.07718400 |

이번 Native 요청의 $0.0192768 선예약 후 실제 Usage로 $0.00013515를 정산하고 차액
$0.01914165를 환급했다. 과거 UNKNOWN을 정산한 것이 아니다.
예산은 초과하지 않았지만 남은 한도는 중단된 실행의 재개 권한이 아니다.

- 기존 journal6줄 prefix SHA256: `b2fe9f54ae8441010bebeb3734896b7328d5445586fd7bc1838045d3d510cf5e` — 그대로 유지.
- 실행 후 journal SHA256: `80607e4746092d22067d61b818e69bd61f493e197f1a6d4d4cc58789c4fa6547`.
- 추가 event는 `EXPANDED_LIVE_BEGIN`, attempt4 `RESERVE`, `SETTLE`뿐이다.
  완료 END를 기록하거나 journal을 초기화하지 않았다.

## Evidence 및 검증 한계

[실제 결과/원본 checkpoint export](expanded-pilot-live-results.json)에 기존 file hash와
file mtime UTC를 포함했다. mtime은 checkpoint 파일 기록 시각이며 HTTP 전송 시작 시각으로 해석하지 않는다.

- 원본은 `.issue68-live/issue68-six-condition-pilot120-gate-v2-v1/`의 승인 Manifest와6개 checkpoint다.
- `runs=[]`는 호출이 없었다는 뜻이 아니다. Native 단위가 정상 완료 전에 HOLD되어 `unit-result`가 생성되지 않았다.
  HTTP/gate-progress/db-progress/partial-native를 사용해 이번1건을 복원했다.
- SDK Usage 상세 Entry와 응답 Snapshot Model은 조기 HOLD 경로의 원본에 저장되지 않았다.
  usage-conflict checkpoint가 없고 HTTP 정산은 성공했지만, 이를 SDK/HTTP 값의 별도 영속 대조 PASS로 주장하지 않는다.
- 요청 모델은 전송 전 검증에서 `gpt-4o-mini`로 고정했고 실제 HTTP200/유효 결과를 받았다.
  미보존 응답 모델 문자열을 추측하지 않는다.
- 정상 완료 경로의 Token·비용·E2E 기록은 무과금 리허설로 검증됐으나,
  조기 HOLD 경로의 `receivedNs`/SDK 상세 기록 누락은 별도 보완 검토가 필요하다.
  승인 Manifest 불변 조건 때문에 이번 실행 중 소스를 변경하거나 재호출하지 않았다.

## 테스트 / 보존 / 다음 단계

- 이번 실행: compileJava/compileTestJava 성공. Live 테스트1건은 최종 `PILOT_COMPLETED` assertion을
  충족하지 못해 **FAIL1 / skipped0 / errors0**, Gradle exit1 / `BUILD FAILED`다. 품질 Gate HOLD를 PASS로 바꾸지 않는다.
- 이전 무과금 전체1,369건/89skipped/실패0 및 build 성공은 [이전 단계 기록](expanded-live-readiness.md)이며 이번 유료 실행 결과와 구분한다.
- 기존 Evidence27개와 기존 private16개(journal 제외) hash 동일. 기존 journal은 승인된 append만 수행했다.
- 테스트/Production/GT/Prompt/Policy/Rule/Model/metadata 소스, Grafana/Compose 및 Batch 기본 OFF 유지.
- Commit/Push/PR/Merge/Issue 변경, AWS, 본 비교/반복 또는 추가 유료 실행을 하지 않았다.
- 품질 Gate가 Native에서 먼저 실패했으므로6조건 효율·품질 비교나 운영 방식 선정은 보류한다.
- 후속은 **Critical14의 Native 품질 실패 검토 및 무과금 부분 snapshot 보완 범위 검토 후 Human 결정**이다.
  GT·정책·Gate를 자동 완화하거나 현재 claim/장부를 초기화하지 않는다. 재개는 별도 승인·계약이 필요하다.
