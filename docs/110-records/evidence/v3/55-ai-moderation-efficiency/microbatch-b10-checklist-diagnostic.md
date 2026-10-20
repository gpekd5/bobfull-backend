# Issue #55 — Batch 10 Item-wise Policy Checklist 진단 비교

## 목적 / 측정 계약

- 기존 Batch 지시문을 Control로 보존하고 Human이 승인한 Item-wise Policy Checklist 한 블록만 추가한 Candidate를 비교한다.
- Primary: 신규 FN·FP·category/risk/actionability 회귀, GT HIGH 미달, 동일 요청 반복 안정성.
- Secondary: Batch Usage / cached input / 공개 단가 Estimated Cost / Provider logical latency / HTTP Attempt.
- 안전 확인: 기존 strict Schema, ID 일대일 매핑, 기존 Validator, 잘림, UNRESOLVED, 반복 wire body 동일성, 누적 $0.50 예산.
- 독립 개발용 진단 평가다. Production 적용이나 일반화된 품질 개선을 주장하지 않는다.
- **진단 20건 × Control/Candidate × 3회 = 항목 판정 120개, 실제 논리 호출 12개**만 실행했다.
- 같은 20건의 반복 60개/조건이지 독립 신규 표본 60개가 아니다.
- 신규40건은 합성 메시지/제안 Ground Truth **Human Review 초안만** 준비했고 API 평가를 하지 않았다.

## 기준 코드 / 고정 조건

- Branch: feature/55-ai-moderation-efficiency.
- HEAD: 339423fbca943d578bf07793ff336490904290eb + uncommitted test source.
- Model alias gpt-4o-mini, 실제 응답 12개 모두 gpt-4o-mini-2024-07-18.
- Prompt: moderation-prompt-v3-short-fragment-boundary. Policy: moderation-policy-v2.
- Production System Prompt / Few-shot 15개 / Rule / Policy / Validator / 기존 Batch Schema·중립 ID·기존 GT를 변경하지 않았다.
- 진단 데이터는 Issue55MicroBatchF0의 고정 20건/순서/구성이다. Rule miss 20/20, FLAGGED GT 9건, SAFE GT 11건.
- Batch 10 × 두 그룹, max_tokens=1280, SDK maxRetries=3, timeout=90s. Sampling 옵션과 Provider seed는 기존과 같이 지정하지 않았다.
- Seed 55는 기존 데이터/계획 기준이며 Provider 결정성을 뜻하지 않는다.
- 각 batch에서 두 조건을 교차 실행하고 repeat + batchIndex에 따라 시작 조건을 뒤집었다. 개별 셀은 세 반복에서 HTTP 본문 SHA-256이 동일했다.
- 이전 유료 실험은 재실행하지 않았고 이번 Control은 새로 6회 측정했다. 과거 시점 Control 결과를 이번 비교의 대용으로 사용하지 않는다.
- API Key는 .env의 OPENAI_API_KEY 항목 하나만 Gradle 자식 환경에 전달했다. 인증 header/Key/원본 요청 본문/실제 이용자 정보를 저장하지 않았다.
- 실제 요청에는 기존 합성 평가 메시지와 중립 ID만 포함한다. 이 JSON Evidence의 응답 항목은 Case ID·예상/실제 enum·Usage·해시이며 메시지 원문을 저장하지 않는다.

## Control / 승인 Candidate 동결

Control System = 원본 ModerationPrompt.SYSTEM_PROMPT + 기존 Batch processing contract.
Candidate System = Control System + 아래 추가 블록. 기존 독립 판단 문구는 그대로 두고 포함/제외 동시 확인, category 간 예외 전이 금지, 상대 비교·개수 맞추기 금지, cross-field 최종 확인을 추가했다.
Production Prompt 자체는 수정하지 않았다. Candidate 동결 후 초안40건을 만들었으며 다시 튜닝하지 않았다.

```text
[Item-wise policy checklist]
각 id의 moderation을 결정할 때 다음을 확인한다.

1. 각 category의 포함 조건과 제외 조건을 함께 확인한다.
   제외 조건에 해당하는 내용만을 근거로 그 category를 부여하지 않는다.
   한 category의 제외 조건을 다른 category의 판단에 대신 적용하지 않는다.

2. 어떤 category에도 명확히 해당하지 않으면 기존의 "애매하면 SAFE"와 SAFE 출력 규칙을 적용한다.
   명확히 해당하는 category가 있으면 그 category에 근거해 FLAGGED 결과를 구성한다.

3. FLAGGED의 riskLevel은 해당 항목에 적용되는 기존 Policy의 위험도 기준으로 정한다.
   다른 항목보다 상대적으로 약하다는 이유나 Batch 안의 SAFE/FLAGGED 개수에 맞추어 판정 또는 위험도를 조정하지 않는다.

4. 반환 전에 각 id의 result, categories, riskLevel 조합을 기존 Output rules와 대조한다.
   PROFANITY에는 LOW를 사용하지 않는다.

설명이나 중간 판단을 출력하지 말고 기존 results JSON만 반환한다.
```

- Control System SHA-256: `5d1fa52b4955d538579b9834eae356745b7f3dd700c2183d8486dc1ed7d0c812`.
- Candidate System SHA-256: `9b6e230b1e3dfa374a9ab3ea331672acbc759b000e03dae0d68a5788059ba133`.
- Batch Schema SHA-256: `6ac8f99e249ceefca8c63302ad1026f51415655d30029c67defc617bd04be924`.
- 호출 시점의 helper/Fake/실행 소스 SHA-256: JSON sourceHashes.
- 원래 평가기의 파서·ID 매핑·Validator 처리 부분만 새 테스트 helper에 그대로 복사했다. 기존 실험 소스 해시 보존을 위한 것으로, 새 Control wire가 기존 evaluate의 wire와 동일함을 Fake 테스트에서 검증했다.
- 실제 network request도 Model/cap/System/User/strict Schema/미지정 sampling을 검사했다.

## 예산 / 호출 결과

- 직전 [F1/F2 C1](microbatch-f1f2-c1.json)의 할인 미반영 누적 상한: $0.12402005.
- 사전 Local tokenizer + framing margin + full output cap 견적: Control $0.00603225 + Candidate $0.00624375 = $0.01227600.
- SDK 최대4 attempt를 가정한 계획 견적: $0.04910400. 기존 누적 포함 $0.17312405 < $0.50.
- 이는 Provider Usage나 엄밀한 token 상한이 아니다. 실제 전송 직전 매 attempt마다 128K input + output cap 비용을 예약하고 Usage 확인 시 할인 미반영 비용으로 정산하는 별도 예산 guard를 적용했다.
- 실행: 2026-10-20T09:28:56.467560Z ~ 2026-10-20T09:29:37.662248Z.
- 실제 논리 12 / HTTP 12 / HTTP 200 12 / Retry 0.
- 누락·중복·미요청 ID·개수 불일치·Provider/Parse/Validation/잘림/UNRESOLVED 모두 0.
- **계약 검증 PASS는 품질 채택 PASS와 다르다. 아래 품질 gate는 실패다.**
- 할인 미반영 이번 추정 비용 $0.00408060, 할인 반영 $0.00330300.
- 할인 미반영 누적 보수 상한: $0.12810065 < $0.50.
- 가격: 1M token당 input $0.15 / cached input $0.075 / output $0.60, [공식 gpt-4o-mini 모델 가격](https://developers.openai.com/api/docs/models/gpt-4o-mini). 실제 청구 금액이 아니다.

## 반복별 품질

Result/Category/Actionability/Risk는 각각 기대 label과의 정확 일치 건수다. P/R/F1은 FLAGGED 이진 분류 기준이다.

| 조건 | Run | Result | Category | Actionability | Risk | TP/FP/FN/TN | Precision / Recall / F1 |
|---|---:|---:|---:|---:|---:|---|---|
| CONTROL | 1 | 15/20 | 15/20 | 15/20 | 13/20 | 7/3/2/8 | 0.700 / 0.778 / 0.737 |
| CONTROL | 2 | 16/20 | 16/20 | 16/20 | 11/20 | 9/4/0/7 | 0.692 / 1.000 / 0.818 |
| CONTROL | 3 | 16/20 | 16/20 | 16/20 | 11/20 | 9/4/0/7 | 0.692 / 1.000 / 0.818 |
| CANDIDATE | 1 | 13/20 | 13/20 | 13/20 | 11/20 | 7/5/2/6 | 0.583 / 0.778 / 0.667 |
| CANDIDATE | 2 | 16/20 | 16/20 | 16/20 | 11/20 | 9/4/0/7 | 0.692 / 1.000 / 0.818 |
| CANDIDATE | 3 | 15/20 | 15/20 | 15/20 | 10/20 | 9/5/0/6 | 0.643 / 1.000 / 0.783 |

| 3회 합산 관측 (각 60항목) | Control | Candidate |
|---|---:|---:|
| Result / Category / Actionability | 47/60 | 44/60 |
| Risk | 35/60 | 32/60 |
| TP / FP / FN / TN | 25 / 11 / 2 / 22 | 25 / 14 / 2 / 19 |
| Precision / Recall / F1 | 0.694 / 0.926 / 0.794 | 0.641 / 0.926 / 0.758 |
| GT HIGH → MEDIUM | 10 | 10 |
| GT HIGH → SAFE/LOW | 2 | 2 |
| Prompt cross-field 위반 | 0 | 0 |
| UNRESOLVED / 계약 실패 | 0 | 0 |

GT HIGH 미달 전체는 조건별 12관측이며 HIGH→MEDIUM 10개와 FN 2개를 합친 값이다. 이를 독립적인 서로 다른 12개 Case로 세지 않는다.
PROFANITY LOW 사용 금지 위반도 별도 검사했고 0이었다. 기존 Validator의 허용 범위를 강화하지 않았다.

## Control 대비 모든 판정 차이 / 회귀

| Run | Case | GT | Control | Candidate | 판정 |
|---|---|---|---|---|---|
| 1 | SAFE-17 | SAFE/[]/LOW | SAFE/[]/LOW | FLAGGED/PROFANITY/MEDIUM | 새 FP: Result/Category/Risk/Actionability 회귀 |
| 1 | SAFE-09 | SAFE/[]/LOW | SAFE/[]/LOW | FLAGGED/PERSONAL_INFORMATION/MEDIUM | 새 FP: Result/Category/Risk/Actionability 회귀 |
| 2 | SAFE-09 | SAFE/[]/LOW | SAFE/[]/LOW | FLAGGED/PERSONAL_INFORMATION/MEDIUM | 새 FP: Result/Category/Risk/Actionability 회귀 |
| 2 | SAFE-10 | SAFE/[]/LOW | FLAGGED/PERSONAL_INFORMATION/MEDIUM | SAFE/[]/LOW | FP 개선 |
| 3 | SAFE-09 | SAFE/[]/LOW | SAFE/[]/LOW | FLAGGED/PERSONAL_INFORMATION/MEDIUM | 새 FP: Result/Category/Risk/Actionability 회귀 |

- 새 FN: 이번 paired Control 대비 0. **그러나 두 조건 모두 R1에서 PROFANITY-14 / PROFANITY-16 FN이 각각 1회 발생했다. FN 0 guardrail 통과가 아니다.**
- 새 FP: 4관측 / 고유 2Case. SAFE-09는 R1/R2/R3 모두, SAFE-17은 R1.
- 개선: SAFE-10 R2의 FP 1관측 개선. Candidate R1/R3에는 FP가 남아 안정적 해결이 아니다.
- Result / Category / Risk / Actionability 회귀 각각 4관측. 동일 4개 tuple 변화에 대한 각 축이며 합해서 16개 별도 오류처럼 해석하지 않는다.
- Control이 HIGH였는데 Candidate가 하향한 추가 paired 회귀는 0. GT HIGH 미달은 양 조건에서 동일하게 남았다.
- 추가적인 category-only/risk-only 차이는 없다. 모든 차이는 위 5행이다.

## 전체20건의 세 반복 원본 판정

S=SAFE/[]/LOW, P=FLAGGED/[PROFANITY], I=FLAGGED/[PERSONAL_INFORMATION], A=FLAGGED/[SPAM], M=MEDIUM, H=HIGH.
표는 R1 / R2 / R3 순서다. 정확한 enum 원값은 JSON에 있다.

| Case | Human GT | Control R1 / R2 / R3 | Candidate R1 / R2 / R3 |
|---|---|---|---|
| SAFE-17 | S | S / P/M / P/M | P/M / P/M / P/M |
| PI-05 | I/M | I/M / I/M / I/M | I/M / I/M / I/M |
| PROFANITY-14 | P/H | S / P/M / P/M | S / P/M / P/M |
| SPAM-16 | A/H | A/M / A/M / A/M | A/M / A/M / A/M |
| SPAM-19 | S | S / S / S | S / S / S |
| SPAM-08 | A/H | A/H / A/H / A/H | A/H / A/H / A/H |
| SAFE-09 | S | S / S / S | I/M / I/M / I/M |
| PROFANITY-16 | P/H | S / P/M / P/M | S / P/M / P/M |
| PROFANITY-01 | P/M | P/M / P/H / P/H | P/M / P/H / P/H |
| PROFANITY-06 | S | S / S / S | S / S / S |
| PROFANITY-13 | S | P/M / P/M / P/M | P/M / P/M / P/M |
| SAFE-01 | S | S / S / S | S / S / S |
| PROFANITY-17 | S | S / S / S | S / S / S |
| SPAM-15 | S | A/M / A/M / A/M | A/M / A/M / A/M |
| PROFANITY-15 | P/H | P/M / P/M / P/M | P/M / P/M / P/M |
| PROFANITY-08 | P/H | P/H / P/H / P/H | P/H / P/H / P/H |
| SPAM-09 | A/H | A/H / A/H / A/H | A/H / A/H / A/H |
| SAFE-10 | S | I/M / I/M / I/M | I/M / S / I/M |
| PROFANITY-05 | S | S / S / S | S / S / S |
| PI-20 | S | S / S / S | S / S / S |

- 동일 tuple 3/3: 두 조건 모두 16/20 Case.
- Control 변동: SAFE-17, PROFANITY-14, PROFANITY-16, PROFANITY-01.
- Candidate 변동: PROFANITY-14, PROFANITY-16, PROFANITY-01, SAFE-10.
- SAFE-09는 Control S/S/S → Candidate I/M·I/M·I/M으로 안정적인 새 오탐이었다. 반복 일치는 정답성을 뜻하지 않는다.
- PROFANITY-16은 양 조건 S→P/M→P/M이며 GT P/H에 한 번도 도달하지 않았다.
- PROFANITY-15는 양 조건 P/M 3/3, GT P/H 미달.
- SAFE-17은 Control S/P/M/P/M 대비 Candidate P/M 3/3.
- SPAM-08은 두 조건 A/H 3/3으로 유지됐다.
- PROFANITY-14도 양 조건 S/P/M/P/M으로 FN과 위험도 미달을 보였다.
- 정책 포함/제외 재확인 지시가 이 표본에서 오탐·위험도 문제를 해결했다는 근거는 없다. 모델 내부의 원인이나 특정 문장의 인과 기여는 확인하지 못했다.

## Usage / Latency

Usage는 Batch 단위만 합산하며 Case에 복사·임의 분배하지 않았다.

| 지표 (각6호출) | Control | Candidate |
|---|---:|---:|
| Input | 6,879 | 8,289 |
| Output | 1,495 | 1,514 |
| Cached Input (input에 포함) | 4,096 | 6,272 |
| Total | 8,374 | 9,803 |
| Prompt / Batch call 평균 | 1,146.5 | 1,381.5 |
| 할인 미반영 Estimated USD | $0.00192885 | $0.00215175 |
| 할인 반영 Estimated USD | $0.00162165 | $0.00168135 |
| logical latency avg ms | 3573.1 | 2985.8 |
| logical latency p95 ms | 4082.0 | 3926.2 |
| 조건별 처리 시간 합 ms | 21459.9 | 17920.4 |

Candidate는 실제 input이 호출당 235token 증가했다. 짧은 지시문이 추가된 만큼 input 감소를 목표로 한 실험은 아니다.
Candidate latency 표본 평균이 낮았지만 조건별 n=6, Cache·시간·backend 통제 한계가 있어 속도 개선으로 확정하지 않는다. p95는 이 작은 n에서는 최대값과 같다.
조건별 처리 시간 합은 교차 실행 중 해당 요청의 합이며 별도 전체 Dataset wall time이나 Kafka E2E latency가 아니다.

### 요청별 실제 관측

| 순서 | 조건 | Run / Batch | Input / Output / Cached / Total | logical ms | HTTP | 할인 미반영 USD | 할인 반영 USD |
|---|---|---|---|---:|---:|---:|---:|
| 1 | CONTROL | 1 / 1 | 1155 / 236 / 0 / 1391 | 3994.3 | 1 | $0.00031485 | $0.00031485 |
| 2 | CANDIDATE | 1 / 1 | 1390 / 249 / 0 / 1639 | 3233.4 | 1 | $0.00035790 | $0.00035790 |
| 3 | CANDIDATE | 1 / 2 | 1373 / 249 / 1152 / 1622 | 2495.3 | 1 | $0.00035535 | $0.00026895 |
| 4 | CONTROL | 1 / 2 | 1138 / 249 / 0 / 1387 | 3864.3 | 1 | $0.00032010 | $0.00032010 |
| 5 | CANDIDATE | 2 / 1 | 1390 / 262 / 1280 / 1652 | 2689.8 | 1 | $0.00036570 | $0.00026970 |
| 6 | CONTROL | 2 / 1 | 1155 / 256 / 1024 / 1411 | 4082.0 | 1 | $0.00032685 | $0.00025005 |
| 7 | CONTROL | 2 / 2 | 1138 / 249 / 1024 / 1387 | 3690.9 | 1 | $0.00032010 | $0.00024330 |
| 8 | CANDIDATE | 2 / 2 | 1373 / 243 / 1280 / 1616 | 2715.2 | 1 | $0.00035175 | $0.00025575 |
| 9 | CONTROL | 3 / 1 | 1155 / 256 / 1024 / 1411 | 2919.5 | 1 | $0.00032685 | $0.00025005 |
| 10 | CANDIDATE | 3 / 1 | 1390 / 262 / 1280 / 1652 | 2855.0 | 1 | $0.00036570 | $0.00026970 |
| 11 | CANDIDATE | 3 / 2 | 1373 / 249 / 1280 / 1622 | 3926.2 | 1 | $0.00035535 | $0.00025935 |
| 12 | CONTROL | 3 / 2 | 1138 / 249 / 1024 / 1387 | 2887.5 | 1 | $0.00032010 | $0.00024330 |

## 실제 실행 명령 / 검증

1. API Key 제거 후 Fake:
```sh
env -u OPENAI_API_KEY ./gradlew :test --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchChecklistTest -PshowTestOutput --console=plain
```
- 최초 compileTestJava는 새 Fake 테스트의 static import 누락으로 FAIL; 새 테스트 import만 고친 뒤 10 PASS.
- Fake12회는 실제 네트워크 OpenAI가 아니라 로컬 HttpServer+동일 Spring AI/SDK를 사용한다.
- 기존 Control wire와 동일, Candidate에서 System 추가 블록 외 차이 없음, 역순 응답 ID 매핑, 누락/중복/미요청 ID/Parse/Validation/잘림, Batch Usage 중복 방지, 변경 요청 거절을 검증했다.

2. .env에서 Key 하나를 안전하게 주입한 자식 프로세스:
```sh
./gradlew --no-daemon :test --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchChecklistOpenAiTest.자식프로세스_Key_존재만_확인하고_Provider는_호출하지_않는다' -PshowTestOutput --console=plain
./gradlew --no-daemon :test --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchChecklistOpenAiTest.진단20건_Control_Candidate를_3회씩_12호출만_비교한다' -PshowTestOutput --console=plain
```
- Key 존재 확인 PASS, Provider 0회. 실제 비교 실행 PASS 1 (12 logical / 12 HTTP).
- 외부 Provider 계약 통과를 검사하는 테스트다. 품질 회귀가 없어야 PASS인 assertion으로 결과를 숨기지 않는다.

3. API Key 제거 후 무과금 회귀:
```sh
env -u OPENAI_API_KEY ./gradlew :test \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchChecklistTest \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchNew40DraftTest \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluationTest \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparisonTest \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchF0Test \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatComparisonTest \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchSizeComparisonTest \
  --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosisTest \
  --tests com.bobfull.chat.infrastructure.ai.ModerationHeldoutDatasetTest --console=plain
env -u OPENAI_API_KEY ./gradlew build -x test --console=plain
```
- 무과금 97 tests / skipped0 / failures0 / errors0 PASS. EOF 공백 정리 후 같은 9개 클래스 필터와 --rerun-tasks로 실제 재실행했다(단순 UP-TO-DATE 결과와 구분).
- root + lambda build -x test PASS. 전체 테스트 재실행은 하지 않았다.
- 신규40건 검증은 기계적 계약 3 PASS이며 Human label 확정을 대신하지 않는다.

## 판정 / 한계 / 다음 단계

- **Candidate 미채택: 품질 개선 gate 실패.** paired 새 FP가 생겼고 기존 FN/HIGH 위험도 미달을 해결하지 못했다. Input/Estimated Cost도 증가했다.
- Control 자체도 FN이 있어 Production Batch 10 적용 가능하다는 뜻이 아니다.
- 새 FN 0은 Control 대비 상대 변화일 뿐 절대 FN 0을 의미하지 않는다.
- 새 FP는 승인된 추가 지시문 조건에서 관측됐으나 그 블록의 특정 문장이 원인임을 분해하지 않았다. 반복/시간/backend 변동을 배제한 일반 인과 결론을 내리지 않는다.
- 개발용 진단20건의 3회 반복이므로 일반화·통계적 유의성·Production 효과를 주장하지 않는다.
- 현재 실험을 근거로 Prompt/Rule/Model/Policy/Validator/Kafka/DB/운영 설정을 변경하지 않는다.
- 신규40건 초안은 별도 Human Review가 먼저다. 이번 Candidate를 새40건으로 재튜닝하거나 자동 평가하지 않는다.
- 추가 호출은 하지 않고 이 결과를 Human에게 보고한다.

## 보존 / 최종 교차 확인

- 착수 시 존재하던 미커밋 파일 35개의 SHA-256이 모두 동일하다. 기존 Evidence/실험 소스/공식 Before는 수정·삭제하지 않았다.
- 추적 파일과 staging diff는 비어 있다. Production/설정/Prompt/Policy/Rule/Validator/Kafka/DB 변경 없음.
- 기존 파일에 추가한 것이 아니라 신규 테스트 전용 Java 4개와 별도 Evidence/초안 5개를 추가했다.
- 원본 build JSON과 Evidence JSON의 값 동일성, 4개 조건 셀의 반복 wire 동일성, 20개 ID 일대일 매핑을 재확인했다.
- 유료 실행 후 신규 Java 3개에서 EOF 빈 줄 한 개만 제거했다. raw JSON sourceHashes는 실행 시점 값 그대로다. 현재 파일 끝에 개행 하나를 되붙이면 실행 시점 해시가 일치함을 검증했다. Prompt/파서/요청 코드 차이는 없다. 변경 전후 해시는 summary JSON의 postRunFormatting에 있다. 무과금 회귀와 build도 그 후 재실행했다.
- 별도 Python 계산으로 Result/Category/Risk/FN/Usage/누적 비용을 검산했다.
- git diff --check PASS. untracked 신규 9개 파일도 git diff --no-index --check /dev/null로 별도 검사하여 whitespace 경고 없음.
- Commit/Push/PR/Issue 변경 없음. 추가 API 호출 및 신규40건 Provider 평가 없음.

## 자료

- [원본 호출별 JSON](microbatch-b10-checklist-diagnostic.json)
- [산출 지표 JSON](microbatch-b10-checklist-summary.json)
- [신규40건 Human Review 표](microbatch-new40-human-review-draft.md)
- [기계 판독용 초안 JSON](microbatch-new40-human-review-draft.json)
- [기존 F1/F2 Evidence](microbatch-f1f2.md), [기존 전체 Evidence](README.md)
