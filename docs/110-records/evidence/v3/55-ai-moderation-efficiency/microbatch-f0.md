# Issue #55 — F0 고정 입력 반복성 검증

## 범위와 측정 계약

- 실행: `2026-10-20T06:10:26.853494Z` ~ `2026-10-20T06:12:10.917943Z` (2026-10-20 KST 15:10~15:12).
- Branch: `feature/55-ai-moderation-efficiency`.
- HEAD: `339423fbca943d578bf07793ff336490904290eb + uncommitted test source`.
- Primary: 같은 입력/그룹/순서에서 단건·Batch 5·Batch 10의 판정 반복성.
- Safety: FN, 단건 대비 새 FP/FN, HIGH 하향, Policy 위반 조합, 누락/중복/Parse/Validation/잘림.
- Secondary: 실제 Usage, HTTP attempt/Retry, latency, 공개 단가 Estimated Cost.
- 기존 [공식 Before/Prompt 실험](./README.md), [Smoke](./microbatch-smoke.md), [79건 전체 비교](./microbatch-comparison.md)는 수정하지 않았다.
- **F0만 실행했다. F1/F2는 실행하지 않았다.** Kafka/DB/Production 코드를 사용하거나 변경한 실험이 아니다.
- 이전 분석에서 선정한 진단용 20건과 기존 Policy v2 Human GT를 재사용했다. 메시지/GT를 새로 작성하거나 수정하지 않았다.
- 이 20건은 모두 Rule miss이며 SAFE 11 / FLAGGED 9 / GT HIGH 7건이다. Rule hit 1건은 F0의 Provider 입력에 없다.
- 사후 선정한 진단 집합 20건이다. 전체 Held-out 80의 정확도나 Production 발생률을 대표하지 않는다. 3회 반복 역시 독립적인 신규 60건이 아니다.

## 고정 조건과 실행 순서

| 항목 | 설정 |
|---|---|
| Model alias / 모든 실제 응답 | `gpt-4o-mini` / `gpt-4o-mini-2024-07-18` |
| Prompt / Policy | `moderation-prompt-v3-short-fragment-boundary` / `moderation-policy-v2` |
| System / Few-shot | Production 원문·15개 유지; Batch에만 기존 Batch 계약 추가 |
| Schema | 기존 단건 ModerationResult / 기존 Batch envelope 그대로 |
| Output cap | 단건 128 / Batch 5 640 / Batch 10 1,280 |
| Sampling / Provider seed | 별도 설정하지 않음; 이전 실험과 동일 |
| SDK retry / timeout | maxRetries=3 / 90초; 변경 없음 |
| 입력 | 앞서 Seed 55 순서에서 선정한 고정 20건; F0에서 재shuffle 없음 |
| Provider ID | `m001`~`m020`; 같은 Case는 모든 F0 반복/Batch 크기에서 같은 ID |
| Batch membership | 아래 표 순서 1~5, 6~10, 11~15, 16~20 / 1~10, 11~20 |
| 순서 | R1 단건→5→10 / R2 5→10→단건 / R3 10→단건→5 |

- 단건 20×3=60, Batch 5 4×3=12, Batch 10 2×3=6: **78 logical calls / 180 Case 판정**.
- 단건은 기존 native 단건 요청이다. 중립 ID를 본문에 새로 추가하지 않았으며 로컬에서 매핑했다. Batch만 기존 id/content envelope를 사용했다.
- F0 내에서는 ID/구성/순서가 동일하다. 이전 전체 79건 실험과는 대상·그룹·일부 중립 ID 대응이 다르므로 같은 Batch 요청의 재현이라고 표현하지 않는다.
- `microbatch-f0.json` manifest에 합성 Case ID, 중립 ID, message SHA-256, GT를 기록했다. 원문·응답 원문·인증값은 저장하지 않았다.
- System/Schema SHA-256도 JSON에 기록했다. Seed 55는 Dataset 순서 기준이며 Provider 결정성을 보장하는 seed가 아니다.
- 실제 클라이언트/기존 single()/evaluate()/Validator를 재사용했다. 결과 계약 실패/Usage 이상/응답 Model 변경/예산 위험이면 checkpoint를 남기고 중단한다.
- 진단 반복에서 품질 오답은 실패로 기록하되 좋은 결과가 나올 때까지 재시도하지 않았다. R1 새 FN 이후에도 예정된 F0 반복만 수행했다.

## 예산과 키 전달

단가는 [OpenAI GPT-4o mini 공식 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)의 input $0.15 / cached input $0.075 / output $0.60 per 1M tokens를 사용했다.
OpenAI 비동기 Batch API가 아니며 해당 할인은 적용하지 않았다.

| 조건 | 예정 logical calls | 로컬 추정 Input | 전체 Output cap 합 | 추정 Input+cap 비용 | 최대 4 attempts 추정 |
|---|---:|---:|---:|---:|---:|
| 1 | 60 | 62,457 | 7,680 | $0.01397655 | $0.05590620 |
| 5 | 12 | 16,257 | 7,680 | $0.00704655 | $0.02818620 |
| 10 | 6 | 8,727 | 7,680 | $0.00591705 | $0.02366820 |

- 사전 tokenizer Input 추정 + framing 여유 + Output cap 비용: **$0.02694015**. 4 attempts 가정: **$0.10776060**.
- 과거 보수적 누적 추정 $0.07891205를 더한 사전 합계 $0.18667265 < $0.50.
- 사전 추정치는 실제 Provider Usage/청구액 또는 엄밀한 상한이 아니다.
- 실제 실행 중에는 HTTP attempt마다 모델 전체 입력 한도 128,000+현재 Output cap의 할인 미반영 비용을 예약했다. 한도 초과 위험이면 요청 전에 차단한다.
- Usage가 있으면 실제 Usage의 할인 미반영 비용으로 정산하고, 없으면 예약액을 유지한다.
- 이번 실제 Estimated Cost: **$0.01278975 할인 미반영 / $0.01209855 할인 반영**.
- 예산 ledger 최종 누적: **$0.09170180** (이전 누적+이번 할인 미반영), $0.50 이내.
- 이전 Prompt 실험에는 일부 HTTP attempt/Cache 사용량의 미확인 한계가 있다. 누적액은 확인 가능한 기록 기반 보수적 추정이지 과거 실제 청구액 전체의 증명은 아니다.
- 루트 `.env`의 `OPENAI_API_KEY` 한 항목만 데이터로 읽어 Gradle 자식 환경에 전달했다. `source`/다른 dotenv 변수 주입/키 출력/인수 전달은 하지 않았다.
- 별도 자식 키 존재 확인 테스트에서 `F0_CHILD_KEY_PRESENT=true; providerCalls=0`를 먼저 확인했다.

## 반복별 품질

Result=SAFE/FLAGGED; Category Exact=집합 일치; Risk Exact=LOW/MEDIUM/HIGH 일치.
Actionability=`riskLevel != LOW` 일치이며 HIGH→MEDIUM도 같은 Review이므로 별도 Risk 비교가 필요하다.
모든 분모는 이번 진단 20건이다.

| 조건 | Result | Category | Risk | Actionability | TP/FP/FN/TN | Precision / Recall / F1 |
|---|---:|---:|---:|---:|---|---|
| 단건 R1 | 13/20 | 13/20 | 10/20 | 14/20 | 9/7/0/4 | 0.563 / 1.000 / 0.720 |
| 단건 R2 | 13/20 | 13/20 | 9/20 | 13/20 | 9/7/0/4 | 0.563 / 1.000 / 0.720 |
| 단건 R3 | 14/20 | 14/20 | 10/20 | 14/20 | 9/6/0/5 | 0.600 / 1.000 / 0.750 |
| Batch 5 R1 | 15/20 | 15/20 | 10/20 | 15/20 | 8/4/1/7 | 0.667 / 0.889 / 0.762 |
| Batch 5 R2 | 15/20 | 15/20 | 11/20 | 15/20 | 8/4/1/7 | 0.667 / 0.889 / 0.762 |
| Batch 5 R3 | 14/20 | 14/20 | 9/20 | 14/20 | 8/5/1/6 | 0.615 / 0.889 / 0.727 |
| Batch 10 R1 | 15/20 | 15/20 | 10/20 | 15/20 | 9/5/0/6 | 0.643 / 1.000 / 0.783 |
| Batch 10 R2 | 15/20 | 15/20 | 10/20 | 15/20 | 9/5/0/6 | 0.643 / 1.000 / 0.783 |
| Batch 10 R3 | 15/20 | 15/20 | 10/20 | 15/20 | 9/5/0/6 | 0.643 / 1.000 / 0.783 |

- 전체 UNRESOLVED / Provider / Parse / Mapping / Validation / Truncated / Incomplete는 각각 0이다.
- 응답 ID의 누락·중복·미요청 ID·개수 불일치는 없었다.
- 실행 테스트 PASS는 응답 계약과 78회 완료를 뜻하며 품질 Guardrail PASS가 아니다.
- Precision=TP/(TP+FP), Recall=TP/(TP+FN), F1=조화평균. UNRESOLVED를 SAFE/TN으로 바꾸지 않는다.

## 전체 20건 반복 판정표

ID는 `HOLDOUT-` 접두사를 생략했다. 모든 셀은 **R1 · R2 · R3** 순서다.
S=SAFE/[]/LOW, P=FLAGGED/[PROFANITY], PI=FLAGGED/[PERSONAL_INFORMATION], SP=FLAGGED/[SPAM]; L/M/H=LOW/MEDIUM/HIGH.

| Case ID | Human GT | 단건 R1·R2·R3 | Batch 5 R1·R2·R3 | Batch 10 R1·R2·R3 |
|---|---|---|---|---|
| SAFE-17 | S | P/M · P/M · S | P/M · P/M · P/M | P/M · P/M · P/M |
| PI-05 | PI/M | PI/M · PI/M · PI/M | PI/M · PI/M · PI/M | PI/M · PI/M · PI/M |
| PROFANITY-14 | P/H | P/M · P/M · P/M | P/M · P/M · P/M | P/M · P/M · P/M |
| SPAM-16 | SP/H | SP/M · SP/M · SP/M | SP/M · SP/M · SP/M | SP/M · SP/M · SP/M |
| SPAM-19 | S | S · SP/M · S | S · S · S | S · S · S |
| SPAM-08 | SP/H | SP/H · SP/H · SP/H | SP/M · SP/H · SP/M | SP/H · SP/H · SP/H |
| SAFE-09 | S | PI/M · PI/M · PI/M | S · S · S | PI/M · PI/M · PI/M |
| PROFANITY-16 | P/H | P/M · P/M · P/M | S · S · S | P/M · P/M · P/M |
| PROFANITY-01 | P/M | P/M · P/M · P/M | P/M · P/M · P/M | P/H · P/H · P/H |
| PROFANITY-06 | S | P/M · P/M · P/M | S · S · P/M | S · S · S |
| PROFANITY-13 | S | P/L · S · P/M | P/M · P/M · P/M | P/M · P/M · P/M |
| SAFE-01 | S | S · S · S | S · S · S | S · S · S |
| PROFANITY-17 | S | S · S · S | S · S · S | S · S · S |
| SPAM-15 | S | SP/M · SP/M · SP/M | SP/M · SP/M · SP/M | SP/M · SP/M · SP/M |
| PROFANITY-15 | P/H | P/H · P/H · P/H | P/M · P/M · P/M | P/M · P/M · P/M |
| PROFANITY-08 | P/H | P/M · P/M · P/M | P/M · P/M · P/M | P/H · P/H · P/H |
| SPAM-09 | SP/H | SP/H · SP/H · SP/H | SP/H · SP/H · SP/H | SP/H · SP/H · SP/H |
| SAFE-10 | S | PI/M · PI/M · PI/M | PI/M · PI/M · PI/M | PI/M · PI/M · PI/M |
| PROFANITY-05 | S | S · S · S | S · S · S | S · S · S |
| PI-20 | S | PI/M · PI/M · PI/M | S · S · S | S · S · S |

### 변동성과 고정 오답 구분

- 단건 full tuple이 변한 Case는 **3/20**: SAFE-17 (P/M→P/M→S), SPAM-19 (S→SP/M→S), PROFANITY-13 (P/L→S→P/M).
- Batch 5 full tuple이 변한 Case는 **2/20**: SPAM-08 (SP/M→SP/H→SP/M), PROFANITY-06 (S→S→P/M).
- Batch 10은 **20/20 Case의 full tuple이 3/3 동일**했다. 그러나 PROFANITY-15의 잘못된 MEDIUM과 오탐도 그대로 반복됐다.
- **동일한 판정 반복은 정답 보장이 아니다.** 이 3회에서 변동 미관측을 결정성/운영 안정성으로 일반화하지 않는다.

### 중점 추적 4건

| Case | 이번 관측 | 해석 |
|---|---|---|
| SAFE-17 | 단건 FP/FP/정답, B5/B10 모두 FP 3/3 | 단건 자체 변동이 존재. paired 새 FP는 R3에만 발생 |
| PROFANITY-15 | 단건 HIGH 3/3, B5/B10 MEDIUM 3/3 | 두 Batch 모두 일관된 새 HIGH→MEDIUM 회귀 |
| SPAM-08 | 단건/B10 HIGH 3/3, B5 MEDIUM/HIGH/MEDIUM | B5 Risk 변동 및 R1/R3 하향. 이번 B10에서는 이전 전체 비교의 하향 미관측 |
| SPAM-16 | 세 조건 모두 MEDIUM 3/3 (GT HIGH) | 단건에도 있는 안정적 Risk 오답. 이번 paired Batch 신규 하향이 아님 |

이전 79건 Batch 10에서 SPAM-16이 HIGH였다는 결과와 이번 결과는 다르다.
입력 집합/그룹이 바뀌었고 Provider 변동 가능성도 있으므로 원인을 문맥 혼입/순서/Batch 크기 하나로 확정하지 않는다.
F0는 같은 구성의 반복성만 검증했다. 구성/순서의 영향은 미검증(F1/F2 미실행)이다.

## 같은 반복 번호 단건 대비 변화

새 FP/FN은 **이번 같은 R의 단건**과 비교했다. 과거 Before의 오답 목록으로 정의하지 않았다.
GT HIGH 하향은 그 R의 단건 HIGH→Batch MEDIUM/LOW인 Case만 센다. 단건도 이미 틀린 Risk는 다음 절에서 별도로 구분한다.

| 조건 | 새 FP | 새 FN | 단건 HIGH→하향 | Category regression 수 | Actionability regression 수 | 기존 FP 개선 |
|---|---|---|---|---:|---:|---|
| Batch 5 R1 | 없음 | `PROFANITY-16` | `SPAM-08`, `PROFANITY-15` | 1 | 2 | `SAFE-09`, `PROFANITY-06`, `PI-20` |
| Batch 5 R2 | `PROFANITY-13` | `PROFANITY-16` | `PROFANITY-15` | 2 | 2 | `SPAM-19`, `SAFE-09`, `PROFANITY-06`, `PI-20` |
| Batch 5 R3 | `SAFE-17` | `PROFANITY-16` | `SPAM-08`, `PROFANITY-15` | 2 | 2 | `SAFE-09`, `PI-20` |
| Batch 10 R1 | 없음 | 없음 | `PROFANITY-15` | 0 | 1 | `PROFANITY-06`, `PI-20` |
| Batch 10 R2 | `PROFANITY-13` | 없음 | `PROFANITY-15` | 1 | 1 | `SPAM-19`, `PROFANITY-06`, `PI-20` |
| Batch 10 R3 | `SAFE-17` | 없음 | `PROFANITY-15` | 1 | 1 | `PROFANITY-06`, `PI-20` |

- 새 FN: **PROFANITY-16, Batch 5 R1/R2/R3 모두**. 단건은 P/M, Batch 5는 S, Human GT는 P/H다.
  단건도 Risk는 틀렸지만 FLAGGED 검출을 유지했다. Batch 5는 이를 SAFE로 바꾸어 중요한 새 미탐을 만들었다.
- 새 FP: 두 Batch 모두 R2 PROFANITY-13, R3 SAFE-17. 두 Case는 단건 자체에도 변동/오탐이 있어 Batch만의 원인이라고 단정하지 않는다.
- 새 HIGH→MEDIUM: PROFANITY-15는 B5/B10 3/3. SPAM-08은 B5 R1/R3만.
- B10의 PROFANITY-01은 단건 M→H가 3/3 발생해 GT MEDIUM과 어긋났다. 하향이 아니더라도 Risk regression으로 기록했다.
- B10의 PROFANITY-08은 M→H 3/3으로 GT와 일치했다. 개선과 다른 Case 회귀를 상쇄하여 품질 통과로 판단하지 않는다.

### 모든 paired tuple 변경

| Batch / 반복 | Case ID | GT | 단건 → Batch |
|---|---|---|---|
| 5 / R1 | SPAM-08 | SP/H | SP/H → SP/M |
| 5 / R1 | SAFE-09 | S | PI/M → S |
| 5 / R1 | PROFANITY-16 | P/H | P/M → S |
| 5 / R1 | PROFANITY-06 | S | P/M → S |
| 5 / R1 | PROFANITY-13 | S | P/L → P/M |
| 5 / R1 | PROFANITY-15 | P/H | P/H → P/M |
| 5 / R1 | PI-20 | S | PI/M → S |
| 10 / R1 | PROFANITY-01 | P/M | P/M → P/H |
| 10 / R1 | PROFANITY-06 | S | P/M → S |
| 10 / R1 | PROFANITY-13 | S | P/L → P/M |
| 10 / R1 | PROFANITY-15 | P/H | P/H → P/M |
| 10 / R1 | PROFANITY-08 | P/H | P/M → P/H |
| 10 / R1 | PI-20 | S | PI/M → S |
| 5 / R2 | SPAM-19 | S | SP/M → S |
| 5 / R2 | SAFE-09 | S | PI/M → S |
| 5 / R2 | PROFANITY-16 | P/H | P/M → S |
| 5 / R2 | PROFANITY-06 | S | P/M → S |
| 5 / R2 | PROFANITY-13 | S | S → P/M |
| 5 / R2 | PROFANITY-15 | P/H | P/H → P/M |
| 5 / R2 | PI-20 | S | PI/M → S |
| 10 / R2 | SPAM-19 | S | SP/M → S |
| 10 / R2 | PROFANITY-01 | P/M | P/M → P/H |
| 10 / R2 | PROFANITY-06 | S | P/M → S |
| 10 / R2 | PROFANITY-13 | S | S → P/M |
| 10 / R2 | PROFANITY-15 | P/H | P/H → P/M |
| 10 / R2 | PROFANITY-08 | P/H | P/M → P/H |
| 10 / R2 | PI-20 | S | PI/M → S |
| 10 / R3 | SAFE-17 | S | S → P/M |
| 10 / R3 | PROFANITY-01 | P/M | P/M → P/H |
| 10 / R3 | PROFANITY-06 | S | P/M → S |
| 10 / R3 | PROFANITY-15 | P/H | P/H → P/M |
| 10 / R3 | PROFANITY-08 | P/H | P/M → P/H |
| 10 / R3 | PI-20 | S | PI/M → S |
| 5 / R3 | SAFE-17 | S | S → P/M |
| 5 / R3 | SPAM-08 | SP/H | SP/H → SP/M |
| 5 / R3 | SAFE-09 | S | PI/M → S |
| 5 / R3 | PROFANITY-16 | P/H | P/M → S |
| 5 / R3 | PROFANITY-15 | P/H | P/H → P/M |
| 5 / R3 | PI-20 | S | PI/M → S |

### GT HIGH의 절대 불일치 및 Policy 조합

- 단건 R1/R2/R3의 GT HIGH 미달은 각각 4/4/4건: PROFANITY-14, SPAM-16, PROFANITY-16, PROFANITY-08.
- B5는 각각 6/5/6건, B10은 각각 4/4/4건. ID 목록은 JSON 각 run의 `gtHighDowngradeIds`에 있다.
- 단건 R1 PROFANITY-13에서 **FLAGGED/[PROFANITY]/LOW** 1건이 나왔다.
  현재 Prompt의 PROFANITY LOW 미사용 규칙과 맞지 않지만 기존 Validator는 통과한다.
  이번에는 Validator를 바꾸지 않고 별도 Policy 진단으로 기록했다.
- 다른 반복/Batch에서는 위 명시적 금지 조합이 없었다. 의미상 모든 정책 위반을 자동 판별했다는 뜻은 아니다.
- SAFE/category/risk의 교차 필드 계약은 기존 Schema와 Validator로 검사했다.

## Token / 비용 / 지연

### 조건별 3회 합계 (각각 같은 20건을 3회 평가)

| 조건 | logical/HTTP | Input | Output | Cached Input | Total | 할인 미반영 비용 | 할인 반영 비용 | 호출 latency avg/p95/p99 ms | Run wall time 합 s |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|
| 단건 | 60/60 | 49,317 | 1,061 | 0 | 50,378 | $0.00803415 | $0.00803415 | 990.2 / 1418.5 / 2625.0 | 65.859 |
| Batch 5 | 12/12 | 12,561 | 1,533 | 4,096 | 14,094 | $0.00280395 | $0.00249675 | 1640.8 / 1762.5 / 1762.5 | 21.052 |
| Batch 10 | 6/6 | 6,879 | 1,533 | 5,120 | 8,412 | $0.00195165 | $0.00156765 | 2516.5 / 2715.9 / 2715.9 | 15.783 |

### 반복별 Usage 및 지연

| 조건 / 반복 | Input/Output/Cached/Total | 비용 미반영 / 반영 | 호출 avg/p95/p99 ms | Run wall s |
|---|---|---|---|---:|
| 1 / R1 | 16439/356/0/16795 | $0.00267945 / $0.00267945 | 1198.1 / 1469.7 / 2625.0 | 26.157 |
| 1 / R2 | 16439/355/0/16794 | $0.00267885 / $0.00267885 | 877.0 / 979.6 / 1023.3 | 19.608 |
| 1 / R3 | 16439/350/0/16789 | $0.00267585 / $0.00267585 | 895.4 / 1044.3 / 1049.5 | 20.094 |
| 5 / R1 | 4187/509/0/4696 | $0.00093345 / $0.00093345 | 1592.6 / 1675.6 / 1675.6 | 6.822 |
| 5 / R2 | 4187/508/2048/4695 | $0.00093285 / $0.00077925 | 1653.3 / 1748.3 / 1748.3 | 7.049 |
| 5 / R3 | 4187/516/2048/4703 | $0.00093765 / $0.00078405 | 1676.6 / 1762.5 / 1762.5 | 7.180 |
| 10 / R1 | 2293/511/1024/2804 | $0.00065055 / $0.00057375 | 2551.2 / 2698.5 / 2698.5 | 5.320 |
| 10 / R2 | 2293/511/2048/2804 | $0.00065055 / $0.00049695 | 2396.1 / 2419.0 / 2419.0 | 5.028 |
| 10 / R3 | 2293/511/2048/2804 | $0.00065055 / $0.00049695 | 2602.1 / 2715.9 / 2715.9 | 5.435 |

- 논리 호출 78 / HTTP 200 78 / SDK Retry 0. 실제 attempt별 status/Usage/nanos는 JSON에 있다.
- Input 합 68,757 / Output 4,127 / Cached Input 9,216 / Total 72,884.
- Cached Input은 Input의 부분집합이며 Total에 다시 더하지 않았다. Cache 미확인 호출 0.
- Batch Usage는 호출마다 한 번 합산했다. Case별로 Usage/Cost를 복제하거나 임의 배분하지 않았다.
- 논리 호출 latency는 기존 ChatClient 호출 구간, percentile은 nearest-rank다. B5 12회/B10 6회라 p95/p99가 max와 같아 운영 percentile로 일반화하지 않는다.
- Run wall time은 checkpoint/검증 등 로컬 오프라인 처리 포함이다. 대기(maxWait), Kafka/Ack/Offset, DB 저장을 포함한 E2E latency가 아니다.
- 교차 실행은 순서 편향 일부를 줄이지만 Cache/네트워크/Provider 부하를 완전히 통제하지 않는다.
- 비용 감소가 있더라도 품질 Guardrail 실패를 정당화하지 않는다.

## 품질 Gate 판정

| 기준 | 단건 | Batch 5 | Batch 10 |
|---|---|---|---|
| FN=0 / 새 FN=0 | PASS | **FAIL**: PROFANITY-16 새 FN 3/3 | PASS |
| paired 새 FP=0 | 기준 경로, 자체 FP 존재 | **FAIL**: R2/R3 각 1건 | **FAIL**: R2/R3 각 1건 |
| 단건 정답 HIGH 하향=0 | 기준 경로, 자체 GT Risk 오답 존재 | **FAIL**: PROFANITY-15 3/3, SPAM-08 2/3 | **FAIL**: PROFANITY-15 3/3 |
| 명시적 PROFANITY/LOW 금지 조합=0 | **FAIL**: R1 1건 | PASS | PASS |
| 계약 오류/UNRESOLVED=0 | PASS | PASS | PASS |
| 같은 Case full tuple 3/3 동일 | 17/20, 변동 3건 | 18/20, 변동 2건 | 20/20, 오답 포함 |
| 전체 품질 / 적용 판단 | 기준 자체의 오탐·변동·Risk 한계 존재 | **F0 Gate 실패, 현 후보 적용 기각** | **F0 Gate 실패, Production 채택 보류** |

Batch 5 R3은 단건 R3보다 F1/Risk Exact도 낮다. 총 Result가 같거나 높더라도 Case-level 회귀를 숨기지 않는다.
단건에서 full tuple 정답이 3/3인 PROFANITY-15를 두 Batch가 모두 3/3 틀리므로 안정 정답 보존 기준도 실패했다.

F0에서 확인한 것은 fixed-composition의 반복 가능한 실패와 native 자체 변동이다.
서로 다른 Batch 구성/순서에 대한 인과 검증, 더 큰 표본과 실제 채팅 결합 검증은 하지 않았다.
**F1/F2의 필요 여부와 추가 호출은 Human이 이번 결과를 본 뒤 결정한다.**
지금의 비용 결과만으로 Production Micro-batching을 구현하거나 적용하지 않는다.

## 실행 / 무과금 회귀

일시 wrapper는 저장소 밖에서 `.env`의 키 한 항목만 읽어 환경변수로 전달했다.
실제 Gradle 자식 명령 (키 값/인수 없음):

```bash
./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchF0OpenAiTest.자식_프로세스에서_Key_존재만_확인하며_Provider는_호출하지_않는다' \
  -PshowTestOutput --console=plain

./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchF0OpenAiTest.F0_고정20건을_단건_Batch5_Batch10_각3회_교차실행한다' \
  -PshowTestOutput --console=plain

env -u OPENAI_API_KEY ./gradlew :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchF0Test' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluationTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationHeldoutDatasetTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetricsTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationPromptTest' \
  --console=plain

env -u OPENAI_API_KEY ./gradlew build -x test --console=plain
```

- 자식 key 존재 확인 1 test PASS, Provider 호출 0.
- F0 실제 평가 1 test PASS / 78 logical calls 완료. 품질 Gate는 위 표와 같이 FAIL.
- 무과금 회귀 **61 tests / skipped 0 / failures 0 / errors 0**.
- 새 F0 순수 테스트 6건: manifest/GT·Rule miss, 교차 순서/고정 입력, 예산, native/Batch 변동 분리, Usage 중복 방지, Policy 조합 진단.
- compileJava / compileTestJava PASS. root + lambda `build -x test` SUCCESS. 전체 test suite는 이번에 실행하지 않았다.
- 별도 Python으로 원본 180개 판정의 Confusion/정답 수/Usage 합/할인 전후 비용을 재계산해 Java 보고서와 일치 확인했다.
- 기존 미커밋 14개 파일의 SHA-256이 모두 같음을 확인했다. Production/설정/GT 무변경이며 기존 tracked/staged diff도 없다.
- Evidence JSON과 runtime report의 구조/값이 일치한다. 원본 180개 판정의 Case 순서가 manifest와 모두 같았다.
- JSON syntax / `git diff --check` / 신규 5개 파일의 untracked whitespace 검사 PASS.
- 신규 파일에 키 값/원문 메시지/인증 필드가 포함되지 않았음을 메모리 내 검사로 확인했다. 일시 wrapper는 삭제했다.
- Commit/Push/PR/Issue 변경 없음.

## 파일

- [F0 전체 구조화 결과](./microbatch-f0.json): 고정 manifest/해시/실행 순서, 78 HTTP attempts, 9 Run, 180 Case 결과, paired 비교와 변동성.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchF0.java`: F0 manifest/분할/교차 순서/통계/변동성.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchF0Test.java`: 무과금 계약 6건.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchF0OpenAiTest.java`: 실제 F0만 실행하는 독립 실험/예산/안전 checkpoint.
