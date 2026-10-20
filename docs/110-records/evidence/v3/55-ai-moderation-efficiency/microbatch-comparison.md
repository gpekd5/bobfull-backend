# Issue #55 — 단건과 Micro-batching 동일 입력 비교

## 측정 계약과 실행 범위

- 실행 구간: `2026-10-20T05:31:25.938147Z` ~ `05:34:15.179211Z` (KST 14:31~14:34).
- HEAD: `339423fbca943d578bf07793ff336490904290eb` + 미커밋 test source.
- Branch: `feature/55-ai-moderation-efficiency`.
- Primary: 동일한 LLM 대상 79건의 실제 Token/Estimated Cost와 품질 Trade-off.
- Safety: 새 FN/FP, Category/Risk/Actionability 회귀, ID 누락·중복, Parse/Validation/잘림/UNRESOLVED.
- 비교: 새 단건 79회 → Batch 2 40회 → Batch 5 16회 → Batch 10 8회, 총 143 logical calls.
- 기존 [공식 Before와 Prompt A/B/C/D 실험](./README.md), [Smoke](./microbatch-smoke.md)는 유지했다.
- 이번 단건 결과를 새 비교 기준으로 사용했다. #48이나 이전 #55 Before 수치에서 개선율을 계산하지 않았다.
- 실제 OpenAI를 사용하는 로컬 독립 평가다. Production/Kafka/DB/운영 설정은 변경하지 않았다.

## 동일 조건과 차이

| 항목 | 단건 | Batch 2 / 5 / 10 |
|---|---|---|
| API | Chat Completions | 동일 |
| 설정 model | `gpt-4o-mini` | 동일 |
| 실제 응답 model | 모든 응답 `gpt-4o-mini-2024-07-18` | 동일 |
| Policy | `moderation-policy-v2` | 동일 |
| System Prompt | `moderation-prompt-v3-short-fragment-boundary` 원문 | 원문 + 기존 테스트 전용 Batch 지시문 |
| Few-shot | 원문 15개 | 같은 15개 유지 |
| Ground Truth | Policy v2 Held-out 80 | 동일 |
| Rule | 기존 `clearFlagged()` 1건 처리 | 같은 1건 처리 |
| LLM 대상 | Rule miss 79건 | 같은 79건 |
| 입력 순서 | Seed 55 shuffle | 같은 순서를 2/5/10건씩 분할 |
| 마지막 Batch | 1건 | 1 / 4 / 9건 |
| Provider 입력 | 메시지 하나 | `items: [{id, content}]`, 중립 ID만 전달 |
| 출력 | 기존 `ModerationResult` JSON | `results: [{id, moderation: ModerationResult}]` |
| Output cap | 128 | 128 × 실제 항목 수 |
| SDK maxRetries / timeout | 3 / 테스트 전용 90초 | 동일 |
| Sampling/Provider seed | 별도 설정하지 않음 | 동일; Seed 55는 Dataset 순서용 |

동일 클라이언트와 같은 생성 Schema 방식(native strict JSON Schema)을 사용했다.
단건은 Production과 같은 System/User/ModerationResult/cap을 사용하되, Parse 실패에도 Usage를 보존하려고
raw `ChatResponse`를 먼저 받고 변환했다. Batch는 1단계 평가 코드를 그대로 재사용했다.
모든 결과에 기존 `ModerationResultValidator`를 적용했다. Production Adapter/Port는 수정하지 않았다.

Rule 대상은 `HOLDOUT-PI-01` 1건이며 모든 후보에서 같은 정답 결과다.
단일 메시지 Dataset이므로 Split-message Rule, Kafka 전달, DB 저장은 포함하지 않는다.
각 후보의 79건은 중복/누락 없이 소진했고, 원문/실제 서비스 식별자는 결과 파일에 저장하지 않았다.

## 예산 사전 확인과 집행

공개 단가: [GPT-4o mini 공식 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)의
Standard input $0.15 / cached input $0.075 / output $0.60 per 1M tokens.
OpenAI 비동기 Batch API가 아니므로 Batch API 할인은 사용하지 않았다.

| 후보 | 예정 호출 | 로컬 추정 Input | Output cap 합계 | 추정 Input + 전체 cap 비용 | 최대 4 attempts 가정 추정 |
|---|---:|---:|---:|---:|---:|
| 단건 | 79 | 82,295 | 10,112 | $0.01841145 | $0.07364580 |
| Batch 2 | 40 | 51,836 | 10,112 | $0.01384260 | $0.05537040 |
| Batch 5 | 16 | 21,716 | 10,112 | $0.00932460 | $0.03729840 |
| Batch 10 | 8 | 11,676 | 10,112 | $0.00781860 | $0.03127440 |
| 합계 | 143 | 167,523 | 40,448 | $0.04939725 | $0.19758900 |

Input은 `jtokkit O200K_BASE`의 System/User/Schema 추정에 호출당 128 token의 framing 여유를 더한 값이다.
Provider 실제 Usage나 엄밀한 청구 상한이 아니다. Cache 할인은 사전 추정에 반영하지 않았다.

- 기존 #55 Prompt 실험 추정액 약 $0.054155 + Smoke 할인 미반영 $0.00074655 = 예산 초기값 $0.05490155.
- 위 사전 4 attempts 추정을 합해 $0.25249055로 $0.50 이내임을 확인하고 실행했다.
- 실행 중에는 **HTTP attempt 직전마다** 모델 전체 입력 한도 128,000 token + 해당 Output cap의
  할인 미반영 비용을 예약했다. 누적액 + 예약액이 $0.50을 넘으면 HTTP 요청 전에 중단한다.
- 응답 Usage를 확인한 attempt는 실제 Usage의 할인 미반영 Estimated Cost로 예약액을 정산한다.
  Usage가 없으면 예약액을 유지하며 미확인 비용을 0으로 만들지 않는다.
- 키는 기존 방식대로 `.env`의 `OPENAI_API_KEY` 한 항목만 자식 프로세스로 전달했다.
  source/eval, 다른 `.env` 항목 주입, 키 출력/명령 인수/결과 저장은 하지 않았다.

## 품질 — 전체 80건 (공통 Rule 1 + LLM 79)

아래 모든 후보는 같은 Policy v2 Ground Truth를 사용한다.

| 후보 | Result Accuracy | Category Exact | Risk Exact | Review Actionability | TP / FP / FN / TN | FLAGGED P / R / F1 |
|---|---|---|---|---|---|---|
| 새 단건 | 72/80 (90.00%) | 72/80 (90.00%) | 68/80 (85.00%) | 72/80 | 42 / 8 / 0 / 30 | 0.840 / 1.000 / 0.913 |
| Batch 2 | 71/80 (88.75%) | 71/80 (88.75%) | 67/80 (83.75%) | 72/80 | 42 / 9 / 0 / 29 | 0.824 / 1.000 / 0.903 |
| Batch 5 | 73/80 (91.25%) | 73/80 (91.25%) | 68/80 (85.00%) | 73/80 | 42 / 7 / 0 / 31 | 0.857 / 1.000 / 0.923 |
| Batch 10 | 74/80 (92.50%) | 74/80 (92.50%) | 69/80 (86.25%) | 74/80 | 42 / 6 / 0 / 32 | 0.875 / 1.000 / 0.933 |

Result는 SAFE/FLAGGED, Category Exact는 category 집합 완전 일치, Risk Exact는 LOW/MEDIUM/HIGH 일치다.
Actionability는 기존 평가와 같이 `riskLevel != LOW`인지 비교한다. HIGH와 MEDIUM은 모두 Review 대상이므로
Actionability 유지가 Risk Exact 유지나 위험도 우선순위 보존을 의미하지 않는다.

Precision=TP/(TP+FP), Recall=TP/(TP+FN), F1은 두 값의 조화평균이다.
UNRESOLVED는 정답 수에 넣지 않으며 Confusion에서 TN/SAFE로 변환하지 않는다.
이번에는 모든 후보의 UNRESOLVED 및 Provider/Parse/Mapping/Validation/잘림/불완전 응답이 각각 0이다.

### LLM 대상 79건만의 품질

| 후보 | Result / Category | Risk Exact | TP / FP / FN / TN | FLAGGED P / R / F1 |
|---|---|---|---|---|
| 새 단건 | 71 / 71 (각 /79) | 67/79 | 41 / 8 / 0 / 30 | 0.837 / 1.000 / 0.911 |
| Batch 2 | 70 / 70 (각 /79) | 66/79 | 41 / 9 / 0 / 29 | 0.820 / 1.000 / 0.901 |
| Batch 5 | 72 / 72 (각 /79) | 67/79 | 41 / 7 / 0 / 31 | 0.854 / 1.000 / 0.921 |
| Batch 10 | 73 / 73 (각 /79) | 68/79 | 41 / 6 / 0 / 32 | 0.872 / 1.000 / 0.932 |

### 유형별 P / R / F1 (전체 80 기준)

| 후보 | PROFANITY | PERSONAL_INFORMATION | SPAM |
|---|---|---|---|
| 새 단건 | 0.786 / 1.000 / 0.880 | 0.842 / 1.000 / 0.914 | 0.882 / 1.000 / 0.938 |
| Batch 2 | 0.733 / 1.000 / 0.846 | 0.889 / 1.000 / 0.941 | 0.833 / 1.000 / 0.909 |
| Batch 5 | 0.733 / 1.000 / 0.846 | 1.000 / 1.000 / 1.000 | 0.833 / 1.000 / 0.909 |
| Batch 10 | 0.786 / 1.000 / 0.880 | 0.941 / 1.000 / 0.970 | 0.882 / 1.000 / 0.938 |

## 이번 단건 대비 Case-level 변화

### 새 FP/FN과 개선

Case ID 접두사 `HOLDOUT-`는 아래 목록에서 유지했다.

| 후보 | 새 FP | 새 FN | 기존 FP 개선 | 전체 판정 tuple 변경 |
|---|---|---|---|---:|
| Batch 2 | `HOLDOUT-SAFE-17`, `HOLDOUT-SPAM-19`, `HOLDOUT-PROFANITY-17` | 없음 | `HOLDOUT-SAFE-09`, `HOLDOUT-PROFANITY-06` | 6건 |
| Batch 5 | `HOLDOUT-SAFE-17`, `HOLDOUT-SPAM-19`, `HOLDOUT-PROFANITY-05` | 없음 | `HOLDOUT-SAFE-09`, `HOLDOUT-PROFANITY-06`, `HOLDOUT-SAFE-10`, `HOLDOUT-PI-20` | 8건 |
| Batch 10 | `HOLDOUT-SAFE-17` | 없음 | `HOLDOUT-SAFE-09`, `HOLDOUT-PROFANITY-06`, `HOLDOUT-SAFE-10` | 7건 |

새 FP/FN은 이번 단건은 맞혔으나 Batch가 틀린 Case다. 과거 Before의 FP 집합으로 계산하지 않았다.

| 후보 | 정답→정답 | 오답→정답 | 오답→오답 | 정답→오답 | Category regression | Risk regression | Actionability regression |
|---|---:|---:|---:|---:|---:|---:|---:|
| Batch 2 | 69 | 2 | 6 | 3 | 3 | 3 | 2 |
| Batch 5 | 69 | 4 | 4 | 3 | 3 | 4 | 3 |
| Batch 10 | 71 | 3 | 5 | 1 | 1 | 3 | 1 |

위 네 전이는 Result+Category 정답 여부 기준이며 합계 80이다. Risk-only 변화까지 포함한 tuple 변경 수와 다르다.
모든 후보의 순수 category 교체(FLAGGED 유지 중 category만 변경)는 없었으며 category regression은 새 FP와 같다.

### 변경된 모든 Case (원문 미포함)

표의 ID는 `HOLDOUT-` 접두사를 생략했다. `S`=SAFE/[]/LOW,
`P`=FLAGGED/[PROFANITY], `PI`=FLAGGED/[PERSONAL_INFORMATION], `SP`=FLAGGED/[SPAM],
`L/M/H`=LOW/MEDIUM/HIGH다. 모든 셀은 result/categories/riskLevel 전체 tuple이다.

| Case ID | Ground Truth | 이번 단건 | Batch 2 | Batch 5 | Batch 10 |
|---|---|---|---|---|---|
| PI-20 | S | PI/M | PI/M | S | PI/M |
| PROFANITY-05 | S | S | S | P/M | S |
| PROFANITY-06 | S | P/M | S | S | S |
| PROFANITY-15 | P/H | P/H | P/M | P/M | P/M |
| PROFANITY-17 | S | S | P/L | S | S |
| SAFE-09 | S | PI/M | S | S | S |
| SAFE-10 | S | PI/M | PI/M | S | S |
| SAFE-17 | S | S | P/M | P/M | P/M |
| SPAM-08 | SP/H | SP/H | SP/H | SP/H | SP/M |
| SPAM-16 | SP/H | SP/M | SP/M | SP/M | SP/H |
| SPAM-19 | S | S | SP/M | SP/M | S |

변경 Case는 총 11 ID이며, 위 표 이외의 결과 tuple은 이번 단건과 같았다.
개선되지 않은 FP를 포함한 전체 FP 목록은 구조화 결과의 각 후보 `metrics.all80.fpIds`에 기록했다.

### Risk와 Policy 주의사항

- 새로운 HIGH→MEDIUM: `HOLDOUT-PROFANITY-15`는 모든 Batch, `HOLDOUT-SPAM-08`은 Batch 10.
- 새로운 LOW→MEDIUM: Batch 2는 SAFE-17/SPAM-19, Batch 5는 SAFE-17/SPAM-19/PROFANITY-05, Batch 10은 SAFE-17.
- MEDIUM→LOW 개선: 모든 Batch에서 SAFE-09/PROFANITY-06, Batch 5/10에서 SAFE-10, Batch 5에서만 PI-20.
- Batch 10에서만 SPAM-16이 MEDIUM→HIGH로 바뀌며 Ground Truth와 일치했다.
- Batch 2의 `HOLDOUT-PROFANITY-17`은 **FLAGGED / [PROFANITY] / LOW**였다.
  현재 Prompt는 PROFANITY의 LOW를 사용하지 않는다고 정의한다. 기존 Validator는 FLAGGED의 category 유무 등을
  검사하며 이 조합 자체는 거절하지 않아 Validation failure가 0이어도 Policy와 의미상 불일치가 남았다.
  따라서 Actionability 정답 수만으로 품질 유지로 판단하지 않는다. 이번에 Validator/Policy는 변경하지 않았다.

### Smoke에서 발견한 3 Case 추적

| Case | Ground Truth | 선행 Batch 10 Smoke | 이번 단건 | Batch 2 | Batch 5 | Batch 10 | 해석 |
|---|---|---|---|---|---|---|---|
| HOLDOUT-SAFE-17 | S | P/M | S | P/M | P/M | P/M | 이번 단건 대비 모든 Batch에서 새 FP |
| HOLDOUT-PROFANITY-14 | P/H | P/M | P/M | P/M | P/M | P/M | 이번 단건에도 있는 Risk 불일치. 새 Batch 회귀가 아님 |
| HOLDOUT-SPAM-16 | SP/H | SP/M | SP/M | SP/M | SP/M | SP/H | Batch 10은 이번 단건/Smoke보다 Risk가 Ground Truth에 일치 |

## Token과 Estimated Cost

| 후보 | logical / HTTP attempts | Input | Output | Cached Input | Total | Cache 할인 미반영 비용 | Cache 할인 반영 비용 |
|---|---|---:|---:|---:|---:|---:|---:|
| 새 단건 | 79 / 79 | 64,994 | 1,312 | 0 | 66,306 | $0.01053630 | $0.01053630 |
| Batch 2 | 40 / 40 | 39,516 | 2,110 | 0 | 41,626 | $0.00719340 | $0.00719340 |
| Batch 5 | 16 / 16 | 16,788 | 2,003 | 0 | 18,791 | $0.00372000 | $0.00372000 |
| Batch 10 | 8 / 8 | 9,212 | 1,965 | 3,072 | 11,177 | $0.00256080 | $0.00233040 |
| 이번 합계 | 143 / 143 | 130,510 | 7,390 | 3,072 | 137,900 | $0.02401050 | $0.02378010 |

Cached Input은 Input의 부분집합이다. Total에 다시 더하지 않았다.
Provider Usage는 Batch 단위로 한 번만 합산했으며 Case별 사용량으로 복제하거나 임의 배분하지 않았다.
Usage/Cache 사용량 미확인 호출은 모두 0이다. SDK Retry 0, HTTP 200 143건이며 응답 모델 변경도 없었다.

```text
할인 미반영 = Input × 0.15/1M + Output × 0.60/1M
할인 반영 = (Input − Cached Input) × 0.15/1M + Cached Input × 0.075/1M + Output × 0.60/1M
```

| 후보 | Input 감소 | Total 감소 | 할인 미반영 비용 감소 | 할인 반영 비용 감소 |
|---|---:|---:|---:|---:|
| Batch 2 | 39.20% | 37.22% | 31.73% | 31.73% |
| Batch 5 | 74.17% | 71.66% | 64.69% | 64.69% |
| Batch 10 | 85.83% | 83.14% | 75.70% | 77.88% |

출력 Token은 envelope/ID와 복수 결과를 포함하여 단건 합계보다 증가했다(각 +60.82% / +52.67% / +49.77%).
반복 입력 분산과 함께 실제 전체 Input/Total 감소를 확인했지만, 고정 System/Schema 각각의 비용을
Provider Usage에서 분리해 측정한 것은 아니다. 각 구성요소별 정확한 기여율을 단정하지 않는다.

모든 비용은 공개 단가 기반 **Estimated Cost**이며 실제 청구액이 아니다.
기존 실험+Smoke+이번 실행의 할인 미반영 누적 추정액은 **$0.07891205**, $0.50 이내다.
이전 Prompt 실험에는 HTTP attempt/Cache 사용량의 일부 미확인 한계가 있으므로 실제 과거 청구액 전체를 보장하는 수치는 아니다.
예산 ledger는 이번 HTTP attempt마다 예약/정산했으며 정상 Usage를 모두 확인했다.

## Latency와 오프라인 처리 시간

| 후보 | 호출당 avg | p95 | p99 | max | 79건 순차 처리 wall time |
|---|---:|---:|---:|---:|---:|
| 새 단건 | 973.4 ms | 1,465.1 ms | 2,708.3 ms | 2,708.3 ms | 77.122 s |
| Batch 2 | 1,163.7 ms | 1,449.4 ms | 2,064.0 ms | 2,064.0 ms | 46.717 s |
| Batch 5 | 1,610.2 ms | 1,806.6 ms | 1,806.6 ms | 1,806.6 ms | 25.829 s |
| Batch 10 | 2,366.4 ms | 2,545.3 ms | 2,545.3 ms | 2,545.3 ms | 18.968 s |

호출당 latency는 논리 ChatClient 호출 구간이며 p95/p99는 nearest-rank다.
Batch 5/10은 호출 수가 적어 p95/p99가 max와 같고, 안정적인 운영 percentile 추정치가 아니다.
wall time은 준비된 입력을 순차 호출하며 검증/결과 checkpoint 저장까지 포함한 시간이다.
실제 유입 대기(maxWait), Kafka/Ack/Offset, DB 저장을 포함한 메시지 E2E latency가 아니다.
Batch 크기가 커지며 호출당 평균 지연은 증가했지만, 이번 오프라인 Dataset 전체 처리 시간은 감소했다.

## 판정과 남은 검증

| 후보 | 1차 판단 | 근거 |
|---|---|---|
| Batch 2 | 현재 후보 기각 | Result/Category 71/80로 기준 72/80 미달, 새FP 3건, 새HIGH→MEDIUM과 PROFANITY/LOW 불일치. 비용 감소만으로 채택 불가 |
| Batch 5 | 보류 | FN 0과 73/80, 비용 감소는 확인. 새FP 3건과 HIGH→MEDIUM 1건이 있어 의미/판정 보존을 주장할 수 없음 |
| Batch 10 | 추가 검증 우선 후보, Production 채택 보류 | 74/80, FN 0, 비용 75.70~77.88% 감소로 이번 후보 중 유리함. 새FP 1건과 새HIGH→MEDIUM 2건이 남음 |

Batch 5/10은 수치상 1차 Held-out 최소 기준을 통과했지만, 이번 작업에서 Stability를 실행하지 않았다.
채택은 이 실험 결과만으로 결정하지 않으며 Production 적용 후보를 확정하지 않았다.

필요한 후속 검증 재료:

- 새FP와 Risk 회귀의 반복성 및 Human 관점 업무 영향. Label을 모델 출력에 맞춰 변경하지 않는다.
- 동일/다른 입력 순서·Batch 구성에서 재실행 및 Stability. 이번 Seed 55 1회 결과를 일반화하지 않는다.
- 실제 traffic/batchSize/maxWait에 따른 단건 유입 대기와 E2E latency.
- Kafka 연동 전 offset/부분 실패/Retry/DLT/멱등성/중복 Side Effect 계약과 장애 복구.

총점 상승과 Case-level 회귀가 공존한다. Batch envelope/추가 지시/그룹 문맥/Provider 변동이 함께 존재하므로
각 변경의 원인을 Batch 크기 하나에 확정하지 않는다. 전체 비교는 단건→2→5→10 순서로 한 번 수행했으며
순서/Cache/네트워크/샘플 수 차이를 통제한 반복 성능 실험은 아니다.

## 실행과 검증

키는 저장소 밖 일시 wrapper에서 읽어 자식 환경변수로만 전달했다. 실제 실행한 Gradle 자식 명령:

```bash
./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparisonOpenAiTest.단건_79건과_Batch_2_5_10을_동일입력으로_비교한다' \
  -PshowTestOutput --console=plain
```

- 유료 평가: 1 test PASS, `BUILD SUCCESSFUL`, 143 logical calls / 143 HTTP attempts.
- HTTP 200: 143, HTTP Retry: 0. 응답 계약/Usage 이상으로 중단된 호출 없음.
- 품질이 좋다는 assertion이 아니라 계약을 지키고 실측을 완료했다는 실행 결과다.
- 결과 파일이 있으면 유료 실행을 자동 반복/덮어쓰지 않게 했다. 각 호출 후 checkpoint를 저장했다.

최종 무과금 회귀:

```bash
env -u OPENAI_API_KEY ./gradlew :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluationTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationHeldoutDatasetTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetricsTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationPromptTest' \
  --console=plain
env -u OPENAI_API_KEY ./gradlew build -x test --console=plain
```

- 최초 신규 단건 Mock은 Spring AI의 `getOptions()` 대신 과거 `getDefaultOptions()`를 stub하여 4건 실패했다.
  테스트 Mock만 실제 dependency API에 맞춘 뒤 PASS했으며 이 과정에는 Provider 호출이 없었다.
- 최종: 55 tests / skipped 0 / failures 0 / errors 0.
- `compileTestJava` PASS. root + lambda `build -x test` SUCCESS. 전체 test suite는 이번에 재실행하지 않았다.
- 저장된 expected/actual/Usage를 별도 Python 계산으로 대조해 Confusion, Result/Category/Risk 정답 수,
  Token 합계, 두 비용 계산과 변경 Case 집합이 일치함을 확인했다. 추가 Provider 호출은 하지 않았다.
- JSON syntax / `git diff --check` 및 신규 파일 whitespace 검사 PASS.
- 기존 Prompt/Smoke Evidence 및 기존 미커밋 소스의 SHA-256 동일성을 확인했다.
- Commit/Push/PR/Issue 변경 없음.

## 파일

- [전체 구조화 실측 결과](./microbatch-comparison.json): HTTP attempt별 Usage/상태, 후보별 호출/Case 결과, 지표, 이번 단건 대비 변화.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchComparison.java`: 단건 평가와 독립 비교 계산.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchComparisonOpenAiTest.java`: 순차 호출, 예산, 중단/checkpoint.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchComparisonTest.java`: 비용/미판정/회귀와 단건 계약 Fake 검증.
