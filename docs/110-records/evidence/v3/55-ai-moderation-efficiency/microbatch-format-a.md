# Issue #55 — A. Native 단건 vs Batch 1 형식 대조

## 목적 / 범위

- 실행: `2026-10-20T07:00:09.795556Z` ~ `2026-10-20T07:01:41.791492Z` (2026-10-20 KST 16:00~16:01).
- Branch: `feature/55-ai-moderation-efficiency`.
- HEAD: `339423fbca943d578bf07793ff336490904290eb + uncommitted test source`.
- Primary: 여러 메시지를 묶기 전에도 Native와 기존 Batch 계약 사이의 품질 차이가 발생하는지 확인.
- Safety: Case별 새 FP/FN, HIGH 하향, Policy 위반 조합, ID 매핑/Parse/Validation/잘림/UNRESOLVED.
- Secondary: Usage, HTTP attempts/Retry, latency, 공개 단가 Estimated Cost.
- **A만 실행했다. B 크기 탐색, C/F1 순서 변경, D/F2 구성 변경은 실행하지 않았다.**
- 기존 [공식 Before/Prompt 실험](./README.md), [Smoke](./microbatch-smoke.md), [전체 79건 비교](./microbatch-comparison.md), [F0](./microbatch-f0.md)를 수정하지 않았다.
- Production/Prompt/Policy/Rule/Validator/Model/운영 설정/Kafka/DB를 변경하지 않았다.

## 고정 조건과 실제 차이

F0의 동일 진단 20건과 Policy v2 Human GT를 재사용했다. 이 20건은 SAFE 11 / FLAGGED 9 / GT HIGH 7건이며 모두 Rule miss다.
원문/GT를 복제해 새로 작성하거나 수정하지 않았다. F0 manifest의 ID/중립 ID/message SHA-256/GT와 정확히 같음을 대조했다.

| 항목 | Native | Batch 1 |
|---|---|---|
| 요청당 메시지 | 1개 | 1개 |
| Model alias / 실제 응답 | `gpt-4o-mini` / 모든 응답 `gpt-4o-mini-2024-07-18` | 동일 |
| Policy | `moderation-policy-v2` | 동일 |
| 기본 Prompt | `moderation-prompt-v3-short-fragment-boundary` Production 원문 | 같은 원문 + 기존 Batch 계약 지시문 |
| Few-shot | 원문 15개 유지 | 같은 15개 유지 |
| User | 기존 메시지 문자열 | `items:[{id,content}]`, 한 항목 |
| Output Schema | 기존 ModerationResult | 기존 `results:[{id,moderation}]` |
| maxTokens | 128 | 128 |
| Sampling / Provider seed | 별도 설정하지 않음 | 동일 |
| SDK maxRetries / timeout | 3 / 90초 | 동일 |
| Provider ID | 본문에 ID를 추가하지 않고 로컬 매핑 | F0의 동일 중립 ID `m001`~`m020` |

System/Schema 해시는 F0와 동일했다. 각 조건·Case의 두 반복은 semantic request specification SHA-256도 같았다.
이 해시는 Model/System/User/Schema/cap 명세를 대상으로 하며 HTTP header까지 포함한 wire hash가 아니다.

이번 변경 변수는 **Batch 형식 묶음**이다. JSON 입력, Schema, 추가 Batch 지시문이 함께 다르므로
개별 요소 하나의 효과나 원인을 분리한 실험은 아니다.

## 교차 실행과 중단 계약

- Native 20×2 + Batch 1 20×2 = **80 logical calls / 40 Case 쌍**.
- Case 순서는 F0의 고정 20건 순서다. 메시지별 두 조건을 인접 실행했다.
- R1은 홀수 순번 Case에서 Native→Batch 1, 짝수 Case에서 Batch 1→Native.
- R2는 각 Case의 두 조건 순서를 반대로 했다. 각 반복에서 선행 조건은 두 모드 각각 10건이다.
- 실험 순서는 JSON `schedule`과 실제 `calls.step`에 남겼다.
- 각 응답에는 기존 Schema/ID 일대일 매핑/ModerationResultValidator를 적용했다.
- 계약 오류/Usage 이상/응답 Model 변경/예산 위험이면 checkpoint를 남기고 중단한다.
- 품질 오답은 관측 결과로 기록하며 좋은 판정이 나올 때까지 재시도하지 않는다.
- 승인된 80회만 실행했고 Smoke 명목으로 유료 호출을 추가하지 않았다.

## 실제 호출 전 Fake Provider 검증

`Issue55MicroBatchFormatComparisonTest`는 loopback HTTP 서버와 실제 Spring AI ChatClient/OpenAiChatModel/OpenAI SDK를 사용했다.
실제 키 대신 가짜 키를 사용했으며 외부 OpenAI에는 연결하지 않았다.

| 검증 | 결과 |
|---|---|
| Batch 1 정상 응답 → ID 매핑/기존 Validator 통과 | PASS |
| ID 누락/결과 개수 부족 | PASS — MAPPING, UNRESOLVED |
| ID 중복/결과 개수 초과 | PASS — MAPPING, UNRESOLVED |
| 미요청 ID | PASS — MAPPING, UNRESOLVED |
| Parse 실패 | PASS — PARSE, UNRESOLVED |
| SAFE + category 등 Validation 위반 | PASS — VALIDATION, UNRESOLVED |
| finish_reason=length | PASS — TRUNCATED, UNRESOLVED |
| 실패 응답의 Usage 보존 | PASS |
| 실제 Fake HTTP의 Model/cap/Sampling 옵션이 인접 두 조건에서 같음 | PASS |
| Batch 지시문/Schema/중립 ID/content가 의도한 그대로 전송됨 | PASS |
| Fake 정답 80회 → 4개 20건 그룹, Usage 중복 없음 | PASS |
| 새 FP/FN/HIGH 하향 및 조건별 반복 변동 집계 | PASS |

위 Fake 정답 결과는 계약 검증용이며 실제 모델 품질로 해석하지 않는다.
신규 계약 테스트는 총 12건이며 유료 실행 전에 통과를 확인했다.

## 예산과 키 전달

[OpenAI 공식 단가](https://developers.openai.com/api/docs/models/gpt-4o-mini):
Input $0.15 / Cached Input $0.075 / Output $0.60 per 1M tokens.
비동기 Batch API가 아니므로 그 할인은 적용하지 않았다.

| 조건 | logical calls | 로컬 tokenizer Input 추정 | 전체 Output cap 합 | 사전 할인 미반영 비용 | 최대 4 attempts 추정 |
|---|---:|---:|---:|---:|---:|
| NATIVE | 40 | 46758 | 5120 | $0.01008570 | $0.04034280 |
| BATCH1 | 40 | 56118 | 5120 | $0.01148970 | $0.04595880 |

- 추정 Input은 O200K_BASE의 System/User/Schema 추정에 호출당 256 token 여유를 더했다.
- 사전 합계 $0.02157540, 최대 4 HTTP attempts 가정 $0.08630160.
- 기존 누적 $0.09170180을 더해 사전 합계 $0.17800340 < $0.50임을 확인한 뒤 실제 호출했다.
- 사전 추정치는 실제 Usage/청구액이나 엄밀한 상한이 아니다.
- 실행 중에는 HTTP attempt 직전마다 전체 입력 한도 128,000+Output cap 128의 할인 미반영 비용을 예약했다.
  정상 Usage가 있으면 실제 Usage로 정산하고, 없거나 잘못됐으면 예약액을 유지한다.
- 이번 실제 Estimated Cost는 **$0.01182060**. Cached Input이 0이라 할인 반영/미반영 비용이 같다.
- 보수적 누적 ledger는 **$0.10352240**, $0.50 이내.
- 과거 Prompt 실험에는 일부 attempt/Cache 기록의 미확인 한계가 있다. 누적값은 확인 가능한 기록 기반 추정이지 전체 실제 청구액 증명은 아니다.
- 저장소 밖 일시 wrapper로 루트 `.env`의 `OPENAI_API_KEY` 한 항목만 읽어 Gradle 자식 환경으로 전달했다.
  `source`/다른 dotenv 변수 주입/키 값 출력/인수 전달을 하지 않았다.
- 먼저 별도 테스트에서 `FORMAT_A_CHILD_KEY_PRESENT=true; providerCalls=0`를 확인했다.

## 반복별 품질

Result=SAFE/FLAGGED, Category Exact=집합 일치, Risk Exact=LOW/MEDIUM/HIGH 일치.
Actionability는 기존 기준 `riskLevel != LOW` 일치다.

| 조건 | Result | Category | Risk | Actionability | TP/FP/FN/TN | Precision / Recall / F1 |
|---|---:|---:|---:|---:|---|---|
| NATIVE R1 | 13/20 | 13/20 | 10/20 | 14/20 | 9/7/0/4 | 0.563 / 1.000 / 0.720 |
| BATCH1 R1 | 19/20 | 19/20 | 16/20 | 19/20 | 9/1/0/10 | 0.900 / 1.000 / 0.947 |
| NATIVE R2 | 15/20 | 15/20 | 11/20 | 15/20 | 9/5/0/6 | 0.643 / 1.000 / 0.783 |
| BATCH1 R2 | 19/20 | 19/20 | 17/20 | 19/20 | 9/1/0/10 | 0.900 / 1.000 / 0.947 |

- Provider/Parse/Mapping/Validation/Truncated/Incomplete/UNRESOLVED는 모두 0.
- 실제 HTTP 200 80건 / Retry 0. Usage/Cache 미확인 호출 0.
- 실행 테스트 PASS는 80회 완료와 응답 계약을 뜻하며 전체 Moderation 품질 보장을 의미하지 않는다.

## 정답 분류: Risk까지 포함한 full tuple 기준

여기서 정답은 **Result + Category 집합 + Risk 모두 GT 일치**다.
Result만 같은 경우나 FLAGGED를 유지하면서 HIGH가 MEDIUM이 된 경우를 전체 정답으로 세지 않는다.

| 반복 | 둘 다 정답 | Native만 정답 | Batch 1만 정답 | 둘 다 오답 | UNRESOLVED |
|---|---:|---:|---:|---:|---:|
| R1 | 9 | 0 | 7 | 4 | 0 |
| R2 | 11 | 0 | 6 | 3 | 0 |

### 그룹별 모든 Case ID

ID는 `HOLDOUT-` 접두사를 생략했다.

| 반복 | 구분 | Case ID |
|---|---|---|
| R1 | 둘 다 정답 | `PI-05`, `SPAM-19`, `SPAM-08`, `PROFANITY-01`, `SAFE-01`, `PROFANITY-17`, `PROFANITY-15`, `SPAM-09`, `PROFANITY-05` |
| R1 | Native만 정답 | 없음 |
| R1 | Batch 1만 정답 | `SAFE-17`, `SAFE-09`, `PROFANITY-16`, `PROFANITY-06`, `PROFANITY-13`, `SAFE-10`, `PI-20` |
| R1 | 둘 다 오답 | `PROFANITY-14`, `SPAM-16`, `SPAM-15`, `PROFANITY-08` |
| R2 | 둘 다 정답 | `SAFE-17`, `PI-05`, `SPAM-19`, `SPAM-08`, `PROFANITY-01`, `PROFANITY-13`, `SAFE-01`, `PROFANITY-17`, `PROFANITY-15`, `SPAM-09`, `PROFANITY-05` |
| R2 | Native만 정답 | 없음 |
| R2 | Batch 1만 정답 | `PROFANITY-14`, `SAFE-09`, `PROFANITY-16`, `PROFANITY-06`, `SAFE-10`, `PI-20` |
| R2 | 둘 다 오답 | `SPAM-16`, `SPAM-15`, `PROFANITY-08` |

Result+Category만 정답 조건으로 보면 다음과 다르다. 두 기준을 섞지 않는다.

| 반복 | 둘 다 정답 | Native만 정답 | Batch 1만 정답 | 둘 다 오답 |
|---|---:|---:|---:|---:|
| R1 | 13 | 0 | 6 | 1 |
| R2 | 15 | 0 | 4 | 1 |

## 전체 Case 판정과 반복 변동

각 결과 셀은 **R1 · R2**다. S=SAFE/[]/LOW,
P=FLAGGED/[PROFANITY], PI=FLAGGED/[PERSONAL_INFORMATION], SP=FLAGGED/[SPAM], L/M/H=LOW/MEDIUM/HIGH.

| Case ID | Human GT | Native R1·R2 | Batch 1 R1·R2 | R1 full tuple 정답 분류 | R2 full tuple 정답 분류 |
|---|---|---|---|---|---|
| SAFE-17 | S | P/M · S | S · S | Batch 1만 정답 | 둘 다 정답 |
| PI-05 | PI/M | PI/M · PI/M | PI/M · PI/M | 둘 다 정답 | 둘 다 정답 |
| PROFANITY-14 | P/H | P/M · P/M | P/M · P/H | 둘 다 오답 | Batch 1만 정답 |
| SPAM-16 | SP/H | SP/M · SP/M | SP/M · SP/M | 둘 다 오답 | 둘 다 오답 |
| SPAM-19 | S | S · S | S · S | 둘 다 정답 | 둘 다 정답 |
| SPAM-08 | SP/H | SP/H · SP/H | SP/H · SP/H | 둘 다 정답 | 둘 다 정답 |
| SAFE-09 | S | PI/M · PI/M | S · S | Batch 1만 정답 | Batch 1만 정답 |
| PROFANITY-16 | P/H | P/M · P/M | P/H · P/H | Batch 1만 정답 | Batch 1만 정답 |
| PROFANITY-01 | P/M | P/M · P/M | P/M · P/M | 둘 다 정답 | 둘 다 정답 |
| PROFANITY-06 | S | P/M · P/M | S · S | Batch 1만 정답 | Batch 1만 정답 |
| PROFANITY-13 | S | P/L · S | S · S | Batch 1만 정답 | 둘 다 정답 |
| SAFE-01 | S | S · S | S · S | 둘 다 정답 | 둘 다 정답 |
| PROFANITY-17 | S | S · S | S · S | 둘 다 정답 | 둘 다 정답 |
| SPAM-15 | S | SP/M · SP/M | SP/M · SP/M | 둘 다 오답 | 둘 다 오답 |
| PROFANITY-15 | P/H | P/H · P/H | P/H · P/H | 둘 다 정답 | 둘 다 정답 |
| PROFANITY-08 | P/H | P/M · P/M | P/M · P/M | 둘 다 오답 | 둘 다 오답 |
| SPAM-09 | SP/H | SP/H · SP/H | SP/H · SP/H | 둘 다 정답 | 둘 다 정답 |
| SAFE-10 | S | PI/M · PI/M | S · S | Batch 1만 정답 | Batch 1만 정답 |
| PROFANITY-05 | S | S · S | S · S | 둘 다 정답 | 둘 다 정답 |
| PI-20 | S | PI/M · PI/M | S · S | Batch 1만 정답 | Batch 1만 정답 |

### 두 조건의 응답 일치

envelope/원시 JSON byte 일치가 아니라, 변환된 ModerationResult를 비교했다.
Category는 집합으로 비교하며 출력 배열/JSON 필드 순서는 판단 기준이 아니다.

| 반복 | Result 일치 | Category 일치 | Risk 일치 | 세 필드 모두 일치 |
|---|---:|---:|---:|---:|
| R1 | 14/20 | 14/20 | 14/20 | 13/20 |
| R2 | 16/20 | 16/20 | 14/20 | 14/20 |

### 동일 조건의 반복 변동

- Native full tuple 변동 **2/20**: SAFE-17 (P/M→S), PROFANITY-13 (P/L→S).
- Batch 1 full tuple 변동 **1/20**: PROFANITY-14 (P/M→P/H).
- Batch 1은 두 번 모두 Result/Category 19/20이지만 Risk가 변했다. 총점 동일을 응답 동일로 해석하지 않는다.
- 두 반복에서 같았다는 사실만으로 결정성/안정성이 보장되었다고 표현하지 않는다.

## 중점 Case와 안전 지표

| Case | GT | Native R1·R2 | Batch 1 R1·R2 | 관측 |
|---|---|---|---|---|
| PROFANITY-16 | P/H | P/M · P/M | P/H · P/H | A에서 Batch 1은 FN 없이 HIGH 유지. Native 대비 Risk 정답 |
| PROFANITY-15 | P/H | P/H · P/H | P/H · P/H | A에서는 두 조건 모두 올바른 HIGH |
| SAFE-17 | S | P/M · S | S · S | Native 자체 변동과 R1 오탐 개선 공존 |
| SPAM-08 | SP/H | SP/H · SP/H | SP/H · SP/H | A에서는 두 조건 모두 올바른 HIGH |

- Batch 1의 Native 대비 새 FP/FN/HIGH 하향은 **R1/R2 모두 0**이다.
- Native FP는 R1 7건, R2 5건. Batch 1 FP는 두 번 모두 SPAM-15 1건이다. FN은 두 조건 모두 0.
- Native R1 PROFANITY-13의 **FLAGGED/[PROFANITY]/LOW**는 기존 Validator를 통과했지만 Prompt의 LOW 미사용 규칙과 맞지 않는다.
  별도 정책 조합 진단으로 기록했고 Validator를 수정하지 않았다.
- Batch 1에서는 위 정책 위반 조합이 없었다.
- GT HIGH의 절대 하향은 Native R1/R2 각각 4건, Batch 1은 각각 3/2건이다.
  따라서 새 하향 0이 모든 GT HIGH를 맞혔다는 뜻은 아니다.
- Batch 1의 남은 Risk 오답은 R1 PROFANITY-14/SPAM-16/PROFANITY-08, R2 SPAM-16/PROFANITY-08이다.

## Token / Estimated Cost / Latency

### 조건별 두 반복 합계

각 조건은 동일 20건을 두 번 실행했다. 40개의 독립된 신규 메시지로 해석하지 않는다.

| 조건 | logical/HTTP | Input | Output | Cached Input | Total | 할인 미반영 비용 | 할인 반영 비용 | 호출 avg/p95/p99 ms |
|---|---|---:|---:|---:|---:|---:|---:|---|
| NATIVE | 40/40 | 32878 | 699 | 0 | 33577 | $0.00535110 | $0.00535110 | 1037.7 / 1679.1 / 2714.2 |
| BATCH1 | 40/40 | 38678 | 1113 | 0 | 39791 | $0.00646950 | $0.00646950 | 1029.5 / 1423.0 / 1647.9 |

### 반복별 상세

| 조건 | Input/Output/Cached/Total | Estimated Cost | 호출 avg/p95/p99 ms | 누적 능동 처리 s |
|---|---|---|---|---:|
| NATIVE R1 | 16439/356/0/16795 | $0.00267945 | 1151.5 / 1806.8 / 2714.2 | 23.053 |
| BATCH1 R1 | 19339/557/0/19896 | $0.00323505 | 1066.7 / 1517.6 / 1647.9 | 21.356 |
| NATIVE R2 | 16439/343/0/16782 | $0.00267165 | 923.9 / 1087.9 / 1163.0 | 18.488 |
| BATCH1 R2 | 19339/556/0/19895 | $0.00323445 | 992.3 / 1158.8 / 1211.5 | 19.857 |

- 실제 Input 합 71,556 / Output 1,812 / Total 73,368 / Cached Input 0.
- Batch Usage는 논리 호출당 한 번 합산했다. Case에 사용량/비용을 복제하거나 임의 분배하지 않았다.
- Batch 1은 호출 수를 줄이지 않았으며 이번 표본에서는 Token/Estimated Cost가 Native보다 더 컸다.
  A는 비용 절감 후보가 아니라 형식 대조군이다.
- logical latency는 기존 ChatClient 호출 구간이며 percentile은 nearest-rank다.
- 표의 능동 처리 시간은 해당 조건 Call의 처리 시간 합이다. 교차 실행한 상대 조건/체크포인트 시간을 포함한 Dataset 전체 wall time이 아니다.
- 실제 요청 시각/반복/선행 조건은 raw JSON에 있다. 교차 배치가 네트워크/Provider 시점 영향을 완전히 제거한 것은 아니다.
- 평균 지연이 비슷하거나 p95가 달라도 운영 성능 향상을 증명한 결과로 일반화하지 않는다.
- 모든 비용은 공개 단가 기반 Estimated Cost이며 실제 청구액이 아니다.

## 해석과 다음 단계 경계

### 확인된 사실

1. 여러 메시지를 함께 넣지 않아도 두 계약의 판정 차이가 관측됐다.
2. 이번 진단 20건에서는 Batch 1이 Native보다 더 많은 full tuple을 맞혔고, Batch 1에서만 새롭게 틀린 Case는 없었다.
3. Native 자체 변동과 Batch 1의 Risk 변동도 존재했다.
4. A에서 PROFANITY-16 FN이나 PROFANITY-15 HIGH 하향은 재현되지 않았다.
5. 두 계약의 호출 수는 같고 Batch 1의 사용 Token/Estimated Cost는 더 컸다.

### 확정하지 않은 내용

- Batch 계약이 일반적으로 품질을 높인다는 결론.
- JSON 입력/중립 ID/추가 Batch 지시/Schema 중 어느 요소가 원인인지.
- F0 Batch 5/10의 회귀가 메시지 수 하나 때문이라는 결론.
- 차이가 전부 형식 효과이며 Provider 실행 변동의 영향은 없다는 결론.
- Production 적용 또는 최적 Batch 크기 결정.

F0의 Batch 5 FN과 Batch 10 Risk 회귀는 A보다 이전 시점·다른 동반 메시지 조건의 관측이다.
A는 그 회귀가 Batch envelope에 반드시 따라오는 현상이라고 볼 수 없음을 보여주지만,
동반 메시지/순서/크기의 개별 원인을 증명하지 않는다.

이후 B를 수행한다면 **Native와 Batch 1을 구분한 채 같은 Batch 계약 안에서 크기를 비교**해야 한다.
현재 A의 표본은 사후 선정 진단 20건, 반복 2회에 한정된다. 전체 Held-out 80이나 실제 채팅 품질로 일반화하지 않는다.
**B/C/D 진행 여부는 Human 확인 후 결정하며 이번에는 실행하지 않았다.**

## 실행 / 검증

키는 일시 wrapper가 자식 환경으로만 전달했다. 실제 Gradle 자식 명령:

```bash
./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatOpenAiTest.자식_프로세스에서_Key_존재만_확인하며_Provider는_호출하지_않는다' \
  -PshowTestOutput --console=plain

./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatOpenAiTest.A_고정20건을_Native와_Batch1_각2회_대조한다' \
  -PshowTestOutput --console=plain

env -u OPENAI_API_KEY ./gradlew :test \
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

- 키 존재 확인 1 test PASS / 실제 Provider 호출 0.
- 실제 A 평가 1 test PASS / 80 logical calls / HTTP 200 80건 / SDK Retry 0.
- 최종 무과금 회귀 **73 tests / skipped 0 / failures 0 / errors 0**. 신규 계약 12건 포함.
- compileJava/compileTestJava PASS. root + lambda `build -x test` SUCCESS.
- 전체 test suite는 이번에 재실행하지 않았다.
- 별도 Python으로 원본 Case 판정의 Confusion/정답 수/그룹 분류/Usage/비용을 재계산하여 Java 결과와 일치 확인했다.
- manifest/GT/Prompt/Schema 해시가 F0와 같고, 각 Case·조건의 두 반복 request specification 해시도 같았다.
- 기존 19개 미커밋 파일의 SHA-256이 모두 같음을 확인했다. Production/설정/GT 및 기존 tracked/staged diff는 없다.
- Evidence JSON과 runtime report의 구조/값이 일치했다. JSON syntax와 `git diff --check`, 신규 5개 파일의 untracked whitespace 검사 PASS.
- 신규 파일에 실제 키/원문 메시지/인증 필드가 포함되지 않았음을 메모리 내 검사로 확인했다. 저장소 밖 일시 wrapper는 삭제했다.
- Commit/Push/PR/Issue 상태 변경 없음.

## 새 파일

- [전체 A 구조화 실측 결과](./microbatch-format-a.json)
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchFormatComparison.java`: Batch 1 연결/80회 교차 계획/사전 추정/쌍 비교/반복 집계.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchFormatComparisonTest.java`: 무과금 Fake HTTP/계약 12건.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchFormatOpenAiTest.java`: A 전용 실제 실행/예산/안전 checkpoint.
