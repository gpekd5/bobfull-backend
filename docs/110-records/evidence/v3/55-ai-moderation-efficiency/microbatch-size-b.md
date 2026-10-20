# Issue #55 — B. 동일 Batch 계약의 크기 탐색

## 목적과 범위

- 실행: `2026-10-20T07:24:57.364360Z` ~ `2026-10-20T07:26:01.309916Z` (2026-10-20 KST 16:24~16:26).
- Branch: `feature/55-ai-moderation-efficiency`.
- HEAD: `339423fbca943d578bf07793ff336490904290eb + uncommitted test source`.
- Primary: 같은 Batch 계약에서 크기 2/5/10/20의 품질·효율과 반복 변동 관측.
- Safety: FN/FP, Risk 하향, Policy 위반 조합, ID 누락·중복·Parse·Validation·잘림·UNRESOLVED.
- Secondary: Provider Usage, HTTP attempts/Retry, latency, 공개 단가 Estimated Cost.
- **B만 실행했다. F1/F2, Production 적용, Native/Batch 1 재호출은 하지 않았다.**
- 기존 [공식 Before/Prompt 실험](./README.md), [Smoke](./microbatch-smoke.md), [79건 전체 비교](./microbatch-comparison.md), [F0](./microbatch-f0.md), [A 형식 대조](./microbatch-format-a.md)를 보존했다.
- Production/Prompt/Few-shot/Rule/Policy/Validator/Model/운영 설정/Kafka/DB는 변경하지 않았다.

## A 선행 관측: Batch 형식 자체의 영향 가능성

A에서 Native와 Batch 1은 모두 메시지 한 개를 처리했지만 Result/Category 정답은 Native 13/15, Batch 1 19/19였다(각 /20).
여러 메시지를 묶기 전부터 판정 차이가 관측됐으므로, Native와 Batch 형식의 차이를 메시지 수의 효과로만 설명할 수 없다.

다만 A의 변경 변수는 입력 JSON/추가 Batch 지시문/출력 Schema의 묶음이며 Provider 실행 변동도 존재했다.
따라서 **Batch 계약 묶음이 판정에 영향을 줄 가능성**으로 기록하고, 개별 구성요소의 인과 효과나 일반적 품질 개선으로 확정하지 않는다.

A는 `2026-10-20T07:00:09Z~07:01:41Z`, B는 그 뒤의 별도 실행이다.
A의 Batch 1은 이번 B의 **참고 기준**이며 같은 시점의 paired 대조군이 아니다.
A/B의 R1/R2 번호가 같아도 같은 실행 block이나 같은 Provider 조건을 뜻하지 않는다.

## 고정 조건과 분할

| 항목 | 조건 |
|---|---|
| 입력 / GT | F0 진단 20건 / 기존 Policy v2 Human GT |
| 분포 | SAFE 11 / FLAGGED 9 / GT HIGH 7, 모두 Rule miss |
| Model alias / 모든 실제 응답 | `gpt-4o-mini` / `gpt-4o-mini-2024-07-18` |
| Production Prompt | `moderation-prompt-v3-short-fragment-boundary` 원문 |
| Policy | `moderation-policy-v2` |
| Few-shot | 기존 15개 원문 유지 |
| Batch 추가 지시문 | 기존 BATCH_INSTRUCTIONS 그대로 |
| Input / Output | 기존 items(id,content) / results(id,moderation) envelope |
| Schema | 모든 크기에서 동일한 기존 BatchOutput JSON Schema |
| Sampling / Provider seed | 별도 설정하지 않음, 이전 실험과 동일 |
| Output cap | 128 × 실제 항목 수 = 256 / 640 / 1,280 / 2,560 |
| SDK maxRetries / timeout | 기존 3 / 90초 |
| 중립 ID | 같은 Case에 F0와 같은 m001~m020 사용 |
| 순서 | F0 고정 순서를 연속 분할; 재shuffle 없음 |

| 크기 | 요청당 항목 | 반복당 호출 | 2회 합 |
|---|---:|---:|---:|
| 2 | 2 | 10 | 20 |
| 5 | 5 | 4 | 8 |
| 10 | 10 | 2 | 4 |
| 20 | 20 | 1 | 2 |
| 합계 | | 17 | **34** |

각 크기·반복은 동일 20건을 중복/누락 없이 소진했다. 총 160개 Case 판정이며 독립적인 신규 160건이 아니다.
Batch 5/10 입력은 F0의 동일 크기 입력과 정확히 같았다. 각 Batch의 두 반복 request specification SHA-256도 같았다.
manifest(ID/중립 ID/message SHA-256/GT), System/Schema 해시는 F0/A와 동일했다.

호출 시점 편향을 줄이려고 각 Batch index마다 크기 조건을 교차했다.
R1은 가능한 조건을 2→5→10→20, R2는 20→10→5→2 순으로 실행했다.
이것은 **HTTP 실행 조건의 교차**이며, 각 크기의 그룹 순서나 그룹 내부 메시지 순서를 바꾼 F1 실험이 아니다.
실제 34개 Step과 시각은 JSON schedule/calls에 있다.

## Batch 20 사전 Fake 검증 / Output cap

로컬 loopback HTTP Fake와 실제 Spring AI/OpenAI SDK로 검증했다. 실제 API Key를 읽거나 외부 OpenAI를 호출하지 않았다.

| 검증 | 결과 |
|---|---|
| 기존 BatchOutput Schema와 strict=true 전송 | PASS |
| max_tokens=2,560 / 입력 20개 / 중립 ID와 원문 일치 | PASS |
| 역순 20개 응답을 ID로 원래 Case 순서에 매핑 | PASS |
| ID 누락·중복·미요청 ID·개수 불일치 거절 | PASS |
| Parse / Validation / length 실패를 미판정으로 보존 | PASS |
| 실패 응답 Usage 보존 / Validation 시 정상 sibling 보존 | PASS |
| Fake 34회 → 8개 20건 그룹 / 160판정 / Usage 한 번 합산 | PASS |
| 모든 크기에서 Model/Sampling 등 공통 wire option 일치 | PASS |

모든 category 3개 + HIGH + pretty JSON의 20건 출력은 로컬 O200K_BASE 추정 **1,229 token**, cap **2,560** 미만이었다.
이는 대표적인 긴 유효 출력에 대한 **tokenizer 사전 점검**이며 Provider Usage나 모든 가능한 출력 길이의 보장은 아니다.
실제 호출에서도 finish_reason=length/불완전 출력이면 성공으로 보정하지 않고 중단하도록 했다.

신규 무과금 계약 테스트 13건을 실제 API 호출 전에 통과시켰다.
Batch 20 유료 Smoke를 별도로 추가하지 않고 승인된 34회 안에서 첫 실제 응답을 검증했다.

## 예산 / 키 보안

공개 단가: [OpenAI GPT-4o mini 공식 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)의
Input $0.15 / Cached Input $0.075 / Output $0.60 per 1M tokens.
비동기 Batch API가 아니며 해당 할인은 사용하지 않았다.

| 크기 | logical calls | tokenizer 추정 Input | Output cap 합 | 할인 미반영 사전 비용 | 최대 4 attempts 추정 |
|---|---:|---:|---:|---:|---:|
| 2 | 20 | 28458 | 5120 | $0.00734070 | $0.02936280 |
| 5 | 8 | 11862 | 5120 | $0.00485130 | $0.01940520 |
| 10 | 4 | 6330 | 5120 | $0.00402150 | $0.01608600 |
| 20 | 2 | 3564 | 5120 | $0.00360660 | $0.01442640 |

- 추정 Input은 로컬 System/User/Schema token 추정에 호출당 256 token 여유를 더했다.
- 사전 합계 $0.01982010 / 최대 4 attempts 가정 $0.07928040.
- 기존 누적 $0.10352240을 더해 $0.18280280 < $0.50임을 확인한 뒤 실행했다.
- 사전 추정은 실제 Usage/청구액이나 엄밀한 상한이 아니다.
- 실행 중에는 HTTP attempt 직전마다 모델 전체 입력 한도 128,000 + 현재 Output cap 비용을 예약했다.
  정상 Usage가 있으면 할인 미반영 실제 Usage 비용으로 정산하며, 없거나 잘못됐으면 예약액을 유지한다.
- 이번 Estimated Cost: **$0.00777330 할인 미반영 / $0.00737010 Cache 반영**.
- 최종 보수적 누적 ledger: **$0.11129570**, $0.50 이내.
- 과거 Prompt 실험에는 일부 attempt/Cache 기록의 미확인 한계가 있어 누적액을 실제 청구액 전체의 증명으로 사용하지 않는다.
- 저장소 밖 일시 wrapper가 루트 .env의 OPENAI_API_KEY **한 항목만** 데이터로 읽어 Gradle 자식 환경에 전달했다.
  source/다른 dotenv 변수 주입/키 값 출력/명령 인수 전달은 하지 않았다.
- 먼저 `SIZE_B_CHILD_KEY_PRESENT=true; providerCalls=0`를 확인했다.

## 크기·반복별 품질

Result=SAFE/FLAGGED, Category Exact=집합 일치, Risk Exact=LOW/MEDIUM/HIGH 일치.
Actionability는 기존 기준 riskLevel!=LOW이며 HIGH→MEDIUM을 검출하지 못하므로 Risk를 별도 비교한다.

| 크기 / 반복 | Result | Category | Risk | Actionability | TP/FP/FN/TN | Precision / Recall / F1 |
|---|---:|---:|---:|---:|---|---|
| 2 / R1 | 16/20 | 16/20 | 11/20 | 16/20 | 9/4/0/7 | 0.692 / 1.000 / 0.818 |
| 2 / R2 | 16/20 | 16/20 | 12/20 | 16/20 | 9/4/0/7 | 0.692 / 1.000 / 0.818 |
| 5 / R1 | 15/20 | 15/20 | 11/20 | 16/20 | 8/4/1/7 | 0.667 / 0.889 / 0.762 |
| 5 / R2 | 14/20 | 14/20 | 10/20 | 14/20 | 8/5/1/6 | 0.615 / 0.889 / 0.727 |
| 10 / R1 | 16/20 | 16/20 | 11/20 | 16/20 | 9/4/0/7 | 0.692 / 1.000 / 0.818 |
| 10 / R2 | 16/20 | 16/20 | 11/20 | 16/20 | 9/4/0/7 | 0.692 / 1.000 / 0.818 |
| 20 / R1 | 13/20 | 13/20 | 11/20 | 13/20 | 7/5/2/6 | 0.583 / 0.778 / 0.667 |
| 20 / R2 | 15/20 | 15/20 | 10/20 | 15/20 | 9/5/0/6 | 0.643 / 1.000 / 0.783 |

Provider/Parse/Mapping/Validation/Truncated/Incomplete/UNRESOLVED는 모두 0.
실제 HTTP 200 34건 / SDK Retry 0 / Usage·Cache 미확인 호출 0이다.
실행 테스트 PASS는 계약과 34회 완료를 뜻하며 품질 Guardrail PASS가 아니다.

## 전체 Case 판정표

ID의 HOLDOUT- 접두사를 생략했다. 각 셀은 **R1 · R2**다.
S=SAFE/[]/LOW, P=FLAGGED/[PROFANITY], PI=FLAGGED/[PERSONAL_INFORMATION], SP=FLAGGED/[SPAM], L/M/H=LOW/MEDIUM/HIGH.

| Case | GT | Batch 2 | Batch 5 | Batch 10 | Batch 20 |
|---|---|---|---|---|---|
| SAFE-17 | S | P/M · P/M | P/M · P/M | P/M · P/M | P/M · P/M |
| PI-05 | PI/M | PI/M · PI/M | PI/M · PI/M | PI/M · PI/M | PI/M · PI/M |
| PROFANITY-14 | P/H | P/M · P/M | P/M · P/M | P/M · P/M | S · P/M |
| SPAM-16 | SP/H | SP/M · SP/M | SP/M · SP/M | SP/M · SP/M | SP/M · SP/M |
| SPAM-19 | S | S · S | S · S | S · S | S · S |
| SPAM-08 | SP/H | SP/M · SP/H | SP/M · SP/H | SP/H · SP/H | SP/H · SP/H |
| SAFE-09 | S | S · S | S · S | S · S | PI/M · PI/M |
| PROFANITY-16 | P/H | P/M · P/M | S · S | P/M · P/M | S · P/M |
| PROFANITY-01 | P/M | P/M · P/M | P/M · P/M | P/H · P/H | P/H · P/H |
| PROFANITY-06 | S | S · S | S · S | S · S | S · S |
| PROFANITY-13 | S | P/M · P/M | P/L · P/M | P/M · P/M | P/M · P/M |
| SAFE-01 | S | S · S | S · S | S · S | S · S |
| PROFANITY-17 | S | S · S | S · S | S · S | S · S |
| SPAM-15 | S | SP/M · SP/M | SP/M · SP/M | SP/M · SP/M | SP/M · SP/M |
| PROFANITY-15 | P/H | P/H · P/H | P/M · P/M | P/M · P/M | P/H · P/M |
| PROFANITY-08 | P/H | P/M · P/M | P/M · P/M | P/H · P/H | P/H · P/H |
| SPAM-09 | SP/H | SP/H · SP/H | SP/H · SP/H | SP/H · SP/H | SP/H · SP/H |
| SAFE-10 | S | S · S | PI/M · PI/M | PI/M · PI/M | PI/M · PI/M |
| PROFANITY-05 | S | S · S | S · S | S · S | S · S |
| PI-20 | S | PI/M · PI/M | S · PI/M | S · S | S · S |

## 반복 변동과 중점 Case

| 크기 | full tuple이 변한 Case | 관측 |
|---|---|---|
| 2 | SPAM-08 | SP/M→SP/H |
| 5 | SPAM-08, PROFANITY-13, PI-20 | SP/M→SP/H, P/L→P/M, S→PI/M |
| 10 | 없음 | 20/20 tuple 동일, 오답 포함 |
| 20 | PROFANITY-14, PROFANITY-16, PROFANITY-15 | S→P/M, S→P/M, P/H→P/M |

### PROFANITY-16

GT는 P/H다. B2/B10은 P/M 2/2, B5는 **S 2/2**, B20은 **S→P/M**이다.
B5에서는 F0 때의 FN이 다시 관측됐고, B20에서도 R1에 FN이 발생했다.
응답 계약 오류가 아니라 유효한 SAFE 분류가 반환된 품질 오류다.

### PROFANITY-15

GT는 P/H다. B2는 P/H 2/2, B5/B10은 **P/M 2/2**, B20은 P/H→P/M이다.
같은 20건을 더 크게 묶는다고 일관되게 좋아지거나 나빠지는 단조 관계는 관측되지 않았다.
B20의 R2 하향은 정확히 같은 요청을 반복했는데도 판정이 달라진 사례다.

### SAFE-17

GT는 S다. 모든 크기에서 **P/M 2/2**로 같은 오탐을 냈다.
B 내부에서는 크기별 차이가 없지만, A의 Batch 1은 S 2/2였다.
A와 시점이 다르므로 이것을 크기만의 효과나 동반 메시지 간섭의 확정 증거로 표현하지 않는다.

### A Batch 1 참고 비교

| Case | A Batch 1 R1·R2 | 이번 B의 관측 |
|---|---|---|
| PROFANITY-16 | P/H · P/H | B2/B10 P/M, B5 FN, B20 FN/위험도 오답 |
| PROFANITY-15 | P/H · P/H | B2 유지, B5/B10 하향, B20 변동 |
| SAFE-17 | S · S | 모든 크기에서 FP |
| SPAM-08 | SP/H · SP/H | B2/B5 변동, B10/B20 유지 |

A에서 두 번 모두 맞힌 Case의 불리한 변화는 후속 진단 재료다.
동시기 대조군이 없으므로 A를 기준으로 paired new FP/FN/개선율을 계산하지 않았다.

## FP / FN / Risk / Policy

| 크기 / 반복 | FP ID | FN ID | GT HIGH 미달 ID | PROFANITY/LOW 금지 조합 |
|---|---|---|---|---|
| 2 / R1 | `SAFE-17`, `PROFANITY-13`, `SPAM-15`, `PI-20` | 없음 | `PROFANITY-14`, `SPAM-16`, `SPAM-08`, `PROFANITY-16`, `PROFANITY-08` | 없음 |
| 2 / R2 | `SAFE-17`, `PROFANITY-13`, `SPAM-15`, `PI-20` | 없음 | `PROFANITY-14`, `SPAM-16`, `PROFANITY-16`, `PROFANITY-08` | 없음 |
| 5 / R1 | `SAFE-17`, `PROFANITY-13`, `SPAM-15`, `SAFE-10` | `PROFANITY-16` | `PROFANITY-14`, `SPAM-16`, `SPAM-08`, `PROFANITY-16`, `PROFANITY-15`, `PROFANITY-08` | `PROFANITY-13` |
| 5 / R2 | `SAFE-17`, `PROFANITY-13`, `SPAM-15`, `SAFE-10`, `PI-20` | `PROFANITY-16` | `PROFANITY-14`, `SPAM-16`, `PROFANITY-16`, `PROFANITY-15`, `PROFANITY-08` | 없음 |
| 10 / R1 | `SAFE-17`, `PROFANITY-13`, `SPAM-15`, `SAFE-10` | 없음 | `PROFANITY-14`, `SPAM-16`, `PROFANITY-16`, `PROFANITY-15` | 없음 |
| 10 / R2 | `SAFE-17`, `PROFANITY-13`, `SPAM-15`, `SAFE-10` | 없음 | `PROFANITY-14`, `SPAM-16`, `PROFANITY-16`, `PROFANITY-15` | 없음 |
| 20 / R1 | `SAFE-17`, `SAFE-09`, `PROFANITY-13`, `SPAM-15`, `SAFE-10` | `PROFANITY-14`, `PROFANITY-16` | `PROFANITY-14`, `SPAM-16`, `PROFANITY-16` | 없음 |
| 20 / R2 | `SAFE-17`, `SAFE-09`, `PROFANITY-13`, `SPAM-15`, `SAFE-10` | 없음 | `PROFANITY-14`, `SPAM-16`, `PROFANITY-16`, `PROFANITY-15` | 없음 |

- B5 R1 PROFANITY-13의 **FLAGGED/[PROFANITY]/LOW**는 Prompt 규칙과 맞지 않지만 기존 Validator는 통과한다.
  Validator를 바꾸지 않고 별도 진단으로 기록했다.
- B20 R1은 PROFANITY-14/16을 SAFE로 판단했고 R2에는 FN이 없었다.
  좋은 반복만 택하거나 평균으로 FN을 숨기지 않는다.
- B2/B10의 FN 0만으로 품질 보존을 주장하지 않는다. FP와 GT HIGH 미달이 남는다.

### 이번 B 내부 크기 쌍 비교

referenceSize의 결과를 기준으로 comparedSize에서 달라진 Case를 비교했다.
이는 인과 효과나 Native 대비 결과가 아니다.
JSON의 재사용 Change record에서 single은 referenceSize 결과, batch는 comparedSize 결과를 뜻한다.

| 반복 / 비교 | tuple 변경 수 | 비교 기준보다 새 FP | 비교 기준보다 새 FN | 기준이 맞힌 GT HIGH의 하향 |
|---|---:|---|---|---|
| R1 2→5 | 5 | `SAFE-10` | `PROFANITY-16` | `PROFANITY-15` |
| R1 2→10 | 6 | `SAFE-10` | 없음 | `PROFANITY-15` |
| R1 2→20 | 8 | `SAFE-09`, `SAFE-10` | `PROFANITY-14`, `PROFANITY-16` | 없음 |
| R1 5→10 | 5 | 없음 | 없음 | 없음 |
| R1 5→20 | 7 | `SAFE-09` | `PROFANITY-14` | 없음 |
| R1 10→20 | 4 | `SAFE-09` | `PROFANITY-14`, `PROFANITY-16` | 없음 |
| R2 2→5 | 3 | `SAFE-10` | `PROFANITY-16` | `PROFANITY-15` |
| R2 2→10 | 5 | `SAFE-10` | 없음 | `PROFANITY-15` |
| R2 2→20 | 6 | `SAFE-09`, `SAFE-10` | 없음 | `PROFANITY-15` |
| R2 5→10 | 4 | 없음 | 없음 | 없음 |
| R2 5→20 | 5 | `SAFE-09` | 없음 | 없음 |
| R2 10→20 | 1 | `SAFE-09` | 없음 | 없음 |

전체 tuple 변경 내역과 expected/reference/compared 값은 JSON comparisons에 기록했다.
예를 들어 B20 R1의 새 FN 두 건은 B2가 이미 MEDIUM으로 위험도를 낮게 판단했던 Case다.
따라서 HIGH→MEDIUM 지표가 0이어도 SAFE로 바뀐 FN 두 건을 무시하지 않는다.

## Token / Estimated Cost / Latency

각 조건의 두 반복 합계이며 같은 20건을 두 번 처리했다.

| 크기 | logical/HTTP | Input | Output | Cached Input | Total | 할인 미반영 비용 | 할인 반영 비용 | 호출 avg/p95/p99 ms |
|---|---|---:|---:|---:|---:|---:|---:|---|
| 2 | 20/20 | 19738 | 1077 | 0 | 20815 | $0.00360690 | $0.00360690 | 1310.5 / 1747.2 / 2239.6 |
| 5 | 8/8 | 8374 | 1022 | 1024 | 9396 | $0.00186930 | $0.00179250 | 1789.4 / 2415.9 / 2415.9 |
| 10 | 4/4 | 4586 | 1010 | 2048 | 5596 | $0.00129390 | $0.00114030 | 2640.0 / 2744.5 / 2744.5 |
| 20 | 2/2 | 2692 | 999 | 2304 | 3691 | $0.00100320 | $0.00083040 | 4300.8 / 4451.2 / 4451.2 |

### 반복별 상세

| 크기 / 반복 | Input/Output/Cached/Total | 비용 미반영 / 반영 | 호출 avg/p95/p99 ms | 누적 능동 처리 s |
|---|---|---|---|---:|
| 2 / R1 | 9869/539/0/10408 | $0.00180375 / $0.00180375 | 1420.9 / 2239.6 / 2239.6 | 14.232 |
| 2 / R2 | 9869/538/0/10407 | $0.00180315 / $0.00180315 | 1200.1 / 1471.0 / 1471.0 | 12.009 |
| 5 / R1 | 4187/508/0/4695 | $0.00093285 / $0.00093285 | 1796.3 / 2415.9 / 2415.9 | 7.191 |
| 5 / R2 | 4187/514/1024/4701 | $0.00093645 / $0.00085965 | 1782.5 / 1915.0 / 1915.0 | 7.134 |
| 10 / R1 | 2293/505/1024/2798 | $0.00064695 / $0.00057015 | 2689.5 / 2744.5 / 2744.5 | 5.383 |
| 10 / R2 | 2293/505/1024/2798 | $0.00064695 / $0.00057015 | 2590.4 / 2742.1 / 2742.1 | 5.184 |
| 20 / R1 | 1346/492/1024/1838 | $0.00049710 / $0.00042030 | 4150.4 / 4150.4 / 4150.4 | 4.153 |
| 20 / R2 | 1346/507/1280/1853 | $0.00050610 / $0.00041010 | 4451.2 / 4451.2 / 4451.2 | 4.453 |

- 전체 Input 35,390 / Output 4,108 / Total 39,498 / Cached Input 5,376.
- Cached Input은 Input의 부분집합이며 Total에 다시 더하지 않는다.
- Batch Usage는 논리 요청마다 한 번 합산했다. Case에 Usage/비용을 복제하거나 임의 분배하지 않았다.
- 비용은 공개 단가 Estimated Cost이며 실제 청구액이 아니다.
- 크기가 커질수록 이번 조건의 Token/비용은 줄었지만 호출 latency는 증가했고 품질 회귀가 공존했다.
- latency는 기존 ChatClient 논리 호출 구간이며 percentile은 nearest-rank다.
  B20은 실제 호출 2건뿐이므로 p95/p99는 max이고 운영 percentile로 일반화할 수 없다.
- 능동 처리 시간은 해당 조건 Call 처리 시간 합이다. 교차 실행한 상대 조건/체크포인트 시간을 포함한 전체 wall time이 아니다.
- 실제 traffic 대기/maxWait, Kafka/Ack/Offset, DB 저장을 포함한 E2E latency는 측정하지 않았다.
- Cache/시점/네트워크를 완전히 통제한 속도 실험이 아니며 A와 비용·latency 개선율을 직접 계산하지 않는다.

## 판정과 한계

| 크기 | 이번 관측에 대한 판단 |
|---|---|
| 2 | FN 0, Policy 금지 조합 0. 그러나 FP 4건씩, GT HIGH 미달 및 Risk 변동이 있어 채택 확정 불가 |
| 5 | PROFANITY-16 FN 2/2, R1 Policy 금지 조합. 현재 품질 Guardrail 미충족 |
| 10 | FN 0, 20건 tuple 동일. PROFANITY-15 하향 2/2와 FP/Risk 오답이 있어 반복 일치만으로 채택 불가 |
| 20 | Token/비용은 가장 작지만 R1 FN 2건과 반복 변동 3건. 현재 품질 Guardrail 미충족 |

**최적 크기는 확정하지 않았다.** Production 적용도 하지 않는다.

B는 크기별로 F0 연속 분할을 사용했다.
크기를 바꾸면 동반 메시지 구성/위치/입력 길이/출력 길이와 전체 Output cap도 함께 달라진다.
실제 잘림은 없었지만, 이 실험으로 크기와 동반 메시지 구성의 개별 인과 효과를 분리하지 못했다.
동일 요청을 반복해도 변동이 관측되어 Provider 실행 변동도 남아 있다.

Batch 20은 진단 20건 전체를 하나로 묶는 유일한 구성이다. 다양한 구성의 강건성을 검증한 것이 아니다.
F1/F2는 실행하지 않았고 그 진행 여부는 Human 확인 후 결정한다.
진단 20건은 사후 선정 집합이며 반복 2회 결과를 전체 Held-out 80이나 Production 품질로 일반화하지 않는다.

## 실행 / 회귀 검증

일시 wrapper에서 .env의 키 한 항목만 자식 환경에 전달했다. 실제 Gradle 자식 명령:

```bash
./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchSizeOpenAiTest.자식_프로세스에서_Key_존재만_확인하며_Provider는_호출하지_않는다' \
  -PshowTestOutput --console=plain

./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchSizeOpenAiTest.B_고정20건을_Batch2_5_10_20_각2회_평가한다' \
  -PshowTestOutput --console=plain

env -u OPENAI_API_KEY ./gradlew :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchSizeComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchF0Test' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluationTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationHeldoutDatasetTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetricsTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationPromptTest' \
  --console=plain

env -u OPENAI_API_KEY ./gradlew build -x test --console=plain
```

- 키 존재 확인 1 test PASS / Provider calls 0.
- 실제 B 평가 1 test PASS / 34 logical calls / HTTP 200 34 / SDK Retry 0.
- 신규 B 무과금 계약 테스트 13 PASS.
- 최종 관련 무과금 회귀 **86 tests / skipped 0 / failures 0 / errors 0**.
- compileJava/compileTestJava PASS. root + lambda build -x test SUCCESS. 전체 test suite는 이번에 실행하지 않았다.
- 독립 Python 재계산으로 160개 판정의 Confusion/정답 수/Usage/비용이 Java 결과와 같음을 확인했다.
- F0/A manifest·GT·Prompt·Schema 동일성, 같은 Batch 두 반복의 request spec 해시 동일성을 확인했다.
- 기존 24개 미커밋 파일의 SHA-256이 모두 같음을 확인했다. Production/설정/GT 및 기존 tracked/staged diff는 없다.
- Evidence JSON과 runtime report의 구조/값이 같았다. JSON syntax / `git diff --check` / 신규 5개 파일의 untracked whitespace 검사 PASS.
- 신규 파일에 실제 키/원문 메시지/인증 필드가 포함되지 않았음을 메모리 내 검사로 확인했다. 저장소 밖 일시 wrapper는 삭제했다.
- Commit/Push/PR/Issue 변경 없음.

## 추가 파일

- [B 전체 구조화 실측 결과](./microbatch-size-b.json)
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchSizeComparison.java`: 고정 분할/34회 교차 계획/예산 추정/크기 쌍 비교/변동 집계.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchSizeComparisonTest.java`: Fake Batch 20/Output cap/매핑·실패/집계 13건.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchSizeOpenAiTest.java`: B 전용 실제 실행/예산/중단/checkpoint.
