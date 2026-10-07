# Issue #48 AI Moderation Evaluation Evidence

## 검증 대상

Issue #48은 현재 production Moderation 정책인 `moderation-policy-v2`를 기준으로 Human Ground Truth와
평가 경로를 정합화하고, 고정 입력셋에서 현재 단일 메시지 Moderation 경로의 Baseline을 측정한다.

이번 작업은 Prompt, Rule, model 또는 production 코드를 개선한 작업이 아니다. Historical Policy v1
결과와 이번 Policy v2 결과는 Ground Truth와 실행 경로가 다르므로 동일 조건의 Before/After 성능 개선으로
비교하지 않는다.

## 측정 계약

- Primary KPI
  - Result Accuracy
  - Category Exact Accuracy
  - Review Actionability
  - FLAGGED Precision / Recall / F1과 TP / FP / FN / TN
- Secondary KPI
  - Category별 Precision / Recall / F1
  - Rule / LLM route 수
  - latency(avg / p95 / p99)
  - Provider가 반환한 token usage와 공개 단가 기반 Estimated Cost
  - Provider / Parse failure
- 안전 확인
  - Policy v1 Held-out/Challenge 104건의 Dataset과 동결 SHA-256을 변경하지 않는다.
  - Policy v2는 같은 80개 메시지 위에 달라진 expected label만 ID 기반 override로 관리한다.
  - Held-out과 Stability 실행 시 Ground Truth와 runtime policy가 모두 `moderation-policy-v2`인지 확인한다.
  - API Key가 없는 일반 test/build에서는 OpenAI evaluation이 실행되지 않는다.

## 기준 코드

- 측정 작업 트리 기준 HEAD: `aebfeaeadee7b546d62594b4db9ff81f760232fe`
- 측정 브랜치: `feature/48-ai-moderation-evaluation`
- 측정 시점에는 Issue #48 테스트 변경이 아직 commit 전 작업 트리에 있었다.
- Runtime Prompt: `moderation-prompt-v3-short-fragment-boundary`
- Runtime Policy: `moderation-policy-v2`
- Evaluation model 설정 기본값: `gpt-4o-mini`
- Output token guard: `128`

실행 요약에는 응답 metadata의 model 값을 별도로 출력하지 않는다. 따라서 이 문서의 model 표기는 테스트
설정 기본값과 비용 출력 기준을 뜻하며, 각 응답 metadata를 독립적으로 보존한 값은 아니다.

## Historical Policy v1 Dataset 보존

기존 `heldoutCases()` 80건과 `challengeCases()` 24건은 Policy v1 기준으로 Human review 후 동결된 역사적
평가 자산이다. 기존 실행의 재현성과 라벨 변경 이력을 보존하기 위해 메시지와 expected label을 수정하지
않았다.

- 동결 Dataset Content SHA-256:
  `78a072fae2d208da79defeb9f7c260594f77c9e964f94d56e1615a55c840527e`
- 기준 Evidence: [Issue #213 Held-out Evidence](../213-ai-moderation-heldout/README.md)
- 계약 테스트는 Policy v1 Held-out/Challenge의 canonical 직렬화 결과가 위 SHA-256과 같은지 계속 검증한다.

## Policy v2 Ground Truth 구성

`heldoutCasesPolicyV2()`는 기존 `heldoutCases()` 80건을 그대로 순회하며, Human review에서 Policy v2와
라벨이 달라진 ID에만 `POLICY_V2_OVERRIDES`를 적용한다. `id`, `message`, `caseType`은 바꾸지 않는다.
Policy v1과 같은 라벨은 override에 중복 기재하지 않는다.

80건 검토 결과는 기존 라벨 유지 64건, Policy v2와 명확한 충돌 10건, Human review 대상 6건이었다.
Human review 6건 중 `HOLDOUT-SPAM-12`는 v1과 v2 라벨이 같아 override하지 않았으므로 실제 override는
15건이다.

계약 테스트가 다음을 검증한다.

- Policy v1/v2 모두 80건이다.
- 순서, ID, message, caseType이 동일하다.
- 실제 라벨 변경 ID 집합과 override key 집합이 동일하다.
- 존재하지 않는 v1 ID를 override하지 않는다.
- 기존 Policy v1 Dataset freeze가 유지된다.

## Evaluation 실행 경로

```text
Held-out Case
→ ModerationRulePolicy.clearFlagged(message)
→ Rule hit: Rule 결과 반환(token 없음)
→ Rule miss: 현재 SYSTEM_PROMPT로 OpenAI Structured Output 호출
→ expected Ground Truth와 Result / Category / Review Actionability 비교
→ Confusion Matrix, Category metric, latency, token 집계
```

Rule 결과는 평가 결과에서 `provider=BOBFULL_RULE_EVALUATION`, `model=rule-filter-v1`, token field는
`null`로 구분한다. Token 합계는 세 token field가 모두 존재하는 LLM 응답만 더한다.

평가 하네스는 production과 같은 `ModerationRulePolicy.clearFlagged()`, SYSTEM_PROMPT, Structured
Output, 기본 model/max token option을 사용한다. 다만 `ChatModerationService.analyze()` 자체를 호출하지
않으므로 split-message context, 완료 결과 skip, `ModerationResultValidator`, DB 저장과 Kafka Retry/DLT는
평가 범위 밖이다.

### Split-message Rule 제외

이번 Dataset은 서로 독립된 단일 메시지 80건/24건이다. 최근 같은 room/sender의 이전 메시지와 30초
window를 구성하지 않고, `ChatModerationService`의 split context 조회와 `clearSplitFlagged()`를 거치지
않는다. 따라서 이번 경로는 production의 단일 메시지 Rule → LLM routing을 평가하지만, split-message
Rule을 포함한 전체 production E2E 평가는 아니다.

## 환경·데이터·실행 조건

- 실행일: 2026-10-07
- 실행 위치: Human 로컬
- Spring profile: `local`
- DB: H2 in-memory, MySQL mode
- Held-out: Policy v2 Ground Truth 80건 1회
- Stability: Policy v2 Ground Truth 고정 20건을 같은 프로세스에서 3회 순차 실행
- Challenge: Historical frozen Challenge 24건 1회
- 실제 OpenAI Provider를 사용한 결과이며 Provider/Parse failure는 각 실행에서 0건이었다.
- API Key 값은 기록하지 않는다.

## Before 결과

`NOT_APPLICABLE`. 이번 작업은 Policy v2용 평가 계약과 Baseline을 확정한 작업이다. Historical Policy v1
결과는 Ground Truth와 일부 routing 조건이 달라 동일 조건의 Before가 아니며, 개선율을 계산하지 않는다.

## 변경 내용

- Policy v1 Dataset과 freeze를 유지한 채 Policy v2 expected label override를 별도 관리한다.
- Held-out과 Stability가 Policy v2 Ground Truth를 사용하고 runtime policy도 Policy v2인지 확인한다.
- 현재 단일 메시지 production 순서와 같이 Rule hit를 먼저 반환하고, miss에서만 LLM을 호출한다.
- Rule / LLM route 수와 token 측정 가능 호출 수를 별도로 출력한다.
- Dataset 계약 테스트로 v1/v2 메시지 동일성과 override 정합성을 검증한다.

Production 코드, Prompt, Rule, Policy와 Dataset 원문은 변경하지 않았다.

## After 결과

여기서 After는 성능 개선 후 수치가 아니라 Issue #48이 정합화한 현재 Policy v2 Baseline을 뜻한다.

### 지표 정의

- Result Accuracy: `SAFE` / `FLAGGED` 결과가 expected와 같은 비율
- Category Exact Accuracy: category set 전체가 expected와 정확히 같은 비율
- Review Actionability: `riskLevel != LOW` 여부가 expected와 같은 비율
- Precision: 실제 FLAGGED 중 Ground Truth도 FLAGGED인 비율, `TP / (TP + FP)`
- Recall: Ground Truth FLAGGED 중 실제로 FLAGGED한 비율, `TP / (TP + FN)`
- F1: Precision과 Recall의 조화 평균
- TP / FP / FN / TN의 positive는 `FLAGGED`다.

### Held-out 80 — Policy v2 Baseline

| 지표 | 결과 |
|---|---:|
| N | 80 |
| Result Accuracy | 73/80 (91.3%) |
| Category Exact Accuracy | 73/80 (91.3%) |
| Review Actionability | 73/80 (91.3%) |
| FLAGGED Precision / Recall / F1 | 0.857 / 1.000 / 0.923 |
| FLAGGED Recall 95% Wilson CI | [0.916, 1.000] |
| TP / FP / FN / TN | 42 / 7 / 0 / 31 |
| Latency | avg 833.4ms / p95 1320ms / p99 2992ms |
| Route Rule / LLM | 1 / 79 |
| Token measured calls | 79 |
| Prompt / Completion / Total token | 64,994 / 1,305 / 66,299 |
| Estimated Cost | $0.010532 |
| Provider / Parse failure | 0 |

Category별 결과:

| Category | Precision | Recall | F1 |
|---|---:|---:|---:|
| PROFANITY | 0.846 | 1.000 | 0.917 |
| PERSONAL_INFORMATION | 0.842 | 1.000 | 0.914 |
| SPAM | 0.882 | 1.000 | 0.938 |

관측된 mismatch 7건은 모두 Ground Truth `SAFE`를 `FLAGGED`로 분류한 FP였다.

| Case ID | Expected | Actual |
|---|---|---|
| `HOLDOUT-SAFE-09` | SAFE / [] / LOW | FLAGGED / PERSONAL_INFORMATION / MEDIUM |
| `HOLDOUT-SAFE-10` | SAFE / [] / LOW | FLAGGED / PERSONAL_INFORMATION / MEDIUM |
| `HOLDOUT-SAFE-15` | SAFE / [] / LOW | FLAGGED / SPAM / MEDIUM |
| `HOLDOUT-SAFE-17` | SAFE / [] / LOW | FLAGGED / PROFANITY / MEDIUM |
| `HOLDOUT-PROFANITY-12` | SAFE / [] / LOW | FLAGGED / PROFANITY / MEDIUM |
| `HOLDOUT-PI-20` | SAFE / [] / LOW | FLAGGED / PERSONAL_INFORMATION / MEDIUM |
| `HOLDOUT-SPAM-15` | SAFE / [] / LOW | FLAGGED / SPAM / MEDIUM |

### Stability 20 × 3 — Policy v2

세 Run의 분류 결과와 실패 Case ID는 동일했다. Latency는 실행마다 달랐다.

| 지표 | Run 1 | Run 2 | Run 3 |
|---|---:|---:|---:|
| Result Accuracy | 19/20 (95.0%) | 19/20 (95.0%) | 19/20 (95.0%) |
| Category Exact Accuracy | 19/20 (95.0%) | 19/20 (95.0%) | 19/20 (95.0%) |
| Review Actionability | 19/20 (95.0%) | 19/20 (95.0%) | 19/20 (95.0%) |
| FLAGGED P / R / F1 | 0.909 / 1.000 / 0.952 | 0.909 / 1.000 / 0.952 | 0.909 / 1.000 / 0.952 |
| TP / FP / FN / TN | 10 / 1 / 0 / 9 | 10 / 1 / 0 / 9 | 10 / 1 / 0 / 9 |
| Latency avg / p95 / p99(ms) | 985.7 / 1755 / 2334 | 700.8 / 921 / 1028 | 852.4 / 1390 / 2146 |
| Rule / LLM | 1 / 19 | 1 / 19 | 1 / 19 |
| Prompt / Completion / Total token | 15,638 / 305 / 15,943 | 15,638 / 305 / 15,943 | 15,638 / 305 / 15,943 |
| Estimated Cost | $0.002529 | $0.002529 | $0.002529 |
| Provider / Parse failure | 0 | 0 | 0 |

Category별 P/R/F1도 세 Run에서 동일했다.

| Category | Precision | Recall | F1 |
|---|---:|---:|---:|
| PROFANITY | 1.000 | 1.000 | 1.000 |
| PERSONAL_INFORMATION | 1.000 | 1.000 | 1.000 |
| SPAM | 0.750 | 1.000 | 0.857 |

세 Run 모두 실패 Case는 `HOLDOUT-SPAM-15` 한 건이며, expected `SAFE / [] / LOW`를 actual
`FLAGGED / SPAM / MEDIUM`으로 분류했다.

### Challenge 24 — Boundary / Robustness

Challenge는 전체 Held-out 정확도 모집단에 합산하지 않는다. Challenge Ground Truth는 Issue #213에서
동결한 Historical label이며, 이번 Issue에서 Policy v2용으로 다시 라벨링하지 않았다. Runtime은 현재
Rule → LLM 경로다. 따라서 아래 수치는 현재 경계 사례 관측치이지, Policy v2 정합 Ground Truth 전체
Baseline으로 일반화하지 않는다.

| 지표 | 결과 |
|---|---:|
| N | 24 |
| Result Accuracy | 22/24 (91.7%) |
| Category Exact Accuracy | 20/24 (83.3%) |
| Review Actionability | 20/24 (83.3%) |
| FLAGGED Precision / Recall / F1 | 0.882 / 1.000 / 0.938 |
| FLAGGED Recall 95% Wilson CI | [0.796, 1.000] |
| TP / FP / FN / TN | 15 / 2 / 0 / 7 |
| Latency | avg 825.2ms / p95 1476ms / p99 3107ms |
| Route Rule / LLM | 2 / 22 |
| Token measured calls | 22 |
| Prompt / Completion / Total token | 18,188 / 379 / 18,567 |
| Estimated Cost | $0.002956 |
| Provider / Parse failure | 0 |

Category별 결과:

| Category | Precision | Recall | F1 |
|---|---:|---:|---:|
| PROFANITY | 1.000 | 0.875 | 0.933 |
| PERSONAL_INFORMATION | 1.000 | 1.000 | 1.000 |
| SPAM | 0.667 | 0.800 | 0.727 |

| Case ID | Expected | Actual | 관측 유형 |
|---|---|---|---|
| `CH-02` | SAFE / [] / LOW | FLAGGED / SPAM / MEDIUM | Result/Category/Actionability mismatch |
| `CH-06` | SAFE / [] / LOW | FLAGGED / SPAM / MEDIUM | Result/Category/Actionability mismatch |
| `CH-22` | FLAGGED / PERSONAL_INFORMATION+PROFANITY / HIGH | FLAGGED / PERSONAL_INFORMATION / MEDIUM | Category/Actionability mismatch |
| `CH-24` | FLAGGED / SPAM+PERSONAL_INFORMATION / HIGH | FLAGGED / PERSONAL_INFORMATION / MEDIUM | Category/Actionability mismatch |

`CH-22`, `CH-24`는 Result는 일치하지만 Rule 경로가 단일 category 결과를 반환해 Historical Challenge의
multi-category expected와 차이가 난 관측 사례다.

### Token과 Estimated Cost 해석

Rule hit는 Provider를 호출하지 않으며 token field가 `null`이다. 위 token 합계는 LLM route 중 Provider
usage metadata의 prompt/completion/total token 세 값이 모두 존재한 호출만 집계했다.

Estimated Cost는 `gpt-4o-mini` 공개 text token 단가(2026-08-10 확인, input $0.15 / output $0.60 per
1M tokens)에 위 집계 token을 곱한 추정치다. 실제 청구 비용이 아니며, SDK 내부 retry attempt별 token과
비용을 독립 관측한 값도 아니다.

## 정합성 회귀 검증

OpenAI API Key를 빈 값으로 명시해 OpenAI evaluation 3개를 skip한 상태에서 검증했다.

```bash
OPENAI_API_KEY='' ./gradlew :test \
  --tests '*ModerationHeldoutDatasetTest' \
  --tests '*ModerationEvaluationMetricsTest' \
  --tests '*ModerationRulePolicyTest' \
  --tests '*ModerationPromptTest' \
  --rerun-tasks

OPENAI_API_KEY='' ./gradlew clean build
```

- 관련 계약·단위 테스트: 22 tests, skipped 0, failures 0, errors 0
  - `ModerationHeldoutDatasetTest`: 10
  - `ModerationEvaluationMetricsTest`: 7
  - `ModerationRulePolicyTest`: 4
  - `ModerationPromptTest`: 1
- 전체 build: `BUILD SUCCESSFUL`
- 전체 root + lambda test 결과: 963 tests, 64 skipped, failures 0, errors 0
- `SpringAiModerationHeldoutEvaluationTest`: 3 tests 모두 API Key 부재 조건으로 skip
- 이번 회귀 검증에서는 OpenAI Provider 호출과 추가 비용이 발생하지 않았다.

## 결과 해석

- 현재 Policy v2 Held-out 80에서는 FN 0, FP 7을 관측했다. 이는 해당 80건 1회 실행의 결과이며 전체
  traffic에서 FN이 없음을 보장하지 않는다.
- Stability 20건은 세 Run에서 분류 결과가 같았고 `HOLDOUT-SPAM-15` 오탐이 반복됐다. 실행별 latency는
  달랐다.
- Held-out에서 Rule hit는 1/80이었다. 이는 현재 Rule 조건과 Dataset 문장 분포에서 관측한 routing이며
  production traffic의 Rule hit 비율이 아니다.
- Challenge에서 경계 SAFE의 SPAM 오탐과 multi-category expected 대비 단일 Rule category 반환을
  관측했다. 이번 Issue에서는 Prompt, Rule, Policy 또는 label을 수정하지 않는다.

## 검증 한계와 후속 후보

- Split-message Rule, Kafka Consumer, DB 저장과 최종 moderation 상태까지 포함한 production E2E 평가는
  아니다.
- Held-out/Challenge는 각 1회 실행이고, Stability도 고정 20건 3회이므로 확률적 출력의 전체 분포를
  보장하지 않는다.
- Challenge Ground Truth는 Historical Policy v1 자산이며 Policy v2용 재라벨링 대상이 아니었다.
- Latency는 Human 로컬과 당시 Provider/network 조건의 관측치이며 production percentile이 아니다.
- token은 응답 usage metadata 기준이다. 실제 Provider HTTP attempt와 retry별 token/cost는 현재 하네스가
  관측하지 않는다.
- 공개 단가 기반 Estimated Cost는 실제 청구액이 아니다.
- 출력 summary만으로 각 응답의 최종 model metadata와 SDK retry attempt를 사후 검증할 수 없다.
- `HOLDOUT-SPAM-15`을 포함한 오탐과 Challenge multi-category 차이는 후속 품질 개선 후보지만, 별도 정책
  판단과 측정 없이 이번 Issue에서 수정하지 않는다.
- route/model/prompt version별 지속 metric, Provider attempt/retry, latency/token/cost 관측은 #49
  Observability 범위다.

## 관련

- Issue #48
- [Issue #213 Held-out / Challenge / Stability Evidence](../213-ai-moderation-heldout/README.md)
- [Issue #251 Rule 선처리 Evidence](../251-ai-moderation-hardening/README.md)
- [Issue #266 Split-message Moderation Evidence](../266-split-message-moderation/README.md)
- 후속 관측 범위: Issue #49
