# Issue #251 Frozen Dataset v1 — AFTER Production CLEAR_FLAGGED Rule

## 실행 계약

- 실행: 2026-08-13 20:38~20:39 KST, 실제 OpenAI Provider 1회 순차 Run
- baseline commit SHA: `32059bcae3642717e952e424ca4d4bea3f1a9953`
- After Implementation SHA: `e348d249c10c6f00c26afc1beb49449879f9127e`
- PR #255 마지막 MAJOR boundary 수정 후 위 SHA 기준으로 2026-08-13 21:20~21:21 KST에 최종 1회 재측정했다.
- Dataset: `issue-251-hardening-v1` / SHA-256 `9caf442202e82faaedd333ee5eaf57b422b06b48669bc7c5c1418e7487afbaba`
- requested model: `gpt-4o-mini`; actual returned model: `gpt-4o-mini-2024-07-18`
- promptVersion / policyVersion: `moderation-prompt-v3-scope` / `moderation-policy-v2`
- production 경로: `ChatModerationService → ModerationRuleFilter → Validator → ChatModeration`
- Context 없음, CLEAR_SAFE 없음, Frozen Dataset/expected 변경 없음.

## Routing / Token

| Metric | BEFORE | AFTER | 변화 |
|---|---:|---:|---:|
| Frozen Case | 66 | 66 | - |
| message traversal | 88 | 88 | - |
| CLEAR_FLAGGED | 0 | 16 | - |
| LLM_REQUIRED | 88 traversals | 72 traversals | -16 |
| 실제 OpenAI calls | 88 | 72 | -18.2% |
| Prompt Tokens | 65,395 | 53,494 | -18.2% |
| Completion Tokens | 1,371 | 1,071 | -21.9% |
| Total Tokens | 66,766 | 54,565 | -18.3% |

단건 workload 기준 routing 후보는 `52 → 35`(32.7%)이고, 위 표는 split fragment를 포함한 실제 Frozen traversal
측정값이다. 비용은 측정 시점의 공식 단가 Source를 이 Evidence에 고정하지 않았으므로 `COST = NOT_CALCULATED`다.

## Quality

| Metric | BEFORE | AFTER |
|---|---:|---:|
| Result Accuracy | 62/66 | 61/66 |
| Category Exact | 61/66 | 61/66 |
| Risk Exact | 61/66 | 61/66 |
| Precision / Recall / F1 | .923 / .973 / .947 | .921 / .946 / .933 |
| FP / FN | 3 / 1 | 3 / 2 |
| Injection Security (determinable) | 4/4 | 4/4 |
| Injection Moderation Exact (result/category) | 9/10 | 9/10 |
| Obfuscation Detection | 12/12 | 12/12 |
| Split Detection | 5/6 | 5/6 |
| Split FP / FN | 2 / 1 | 2 / 1 |

## Rule Fast Path Evidence

- Fast Path: 16
- Fast Path Precision: 16/16 = 1.000
- Fast Path False Positive: 0
- Human SAFE Fast Path: 0
- OpenAI calls on Fast Path: 0
- Rule result category/risk mismatch: 0

각 16건은 `BOBFULL_RULE / rule-filter-v1 / NO_LLM / moderation-policy-v2 / token=null` metadata로 저장됐고,
기존 `ModerationResultValidator`를 거쳤다.

## Final Re-review Guard Validation

- 개인정보 부정문 guard와 profanity + spam 복합 signal guard를 추가했다. 이는 확실하지 않은 단일 category
  확정을 막고 `LLM_REQUIRED`로 위임하는 production 경계 축소다.
- 동일 Frozen Dataset의 deterministic routing은 `CLEAR_FLAGGED=16`, `LLM_REQUIRED=50 cases / 72 traversals`로
  변경되지 않았다. Dataset SHA도 `9caf442202e82faaedd333ee5eaf57b422b06b48669bc7c5c1418e7487afbaba`로 유지됐다.
- 따라서 Provider AFTER를 재실행하지 않았으며, 이 문서의 기존 72 calls / 54,565 total tokens는 마지막
  실제 Provider 단일 측정값으로 유지한다.

## INJ-06

| 항목 | AFTER actual |
|---|---|
| route | LLM_REQUIRED |
| Provider raw output | `FLAGGED / [PROFANITY] / MEDIUM` |
| Application Validation | PASS |
| 저장 | `FLAGGED / [PROFANITY] / MEDIUM` |

Provider 오류 0, 응답 형식 오류 0, 애플리케이션 검증 실패 0이다. BEFORE의 빈 category 응답은
과거 관측으로 유지하며, AFTER raw output의 변화는 LLM_REQUIRED Provider variation 후보로 분리한다.

## Latency

- 전체 traversal avg / p50 / p95: 718.7ms / 761ms / 1,448ms
- Rule Fast Path avg: 0.0ms (`ChatModeration.latencyMillis` 밀리초 단위 반올림; 외부 Provider 호출 없음)
- LLM path avg: 878.5ms

외부 Provider latency 변동을 성능 개선으로 과장하지 않는다. Rule Fast Path의 구조적 이점은 외부 호출 0회이며,
전체 workload latency는 해당 단일 측정값이다.

## Attribution / 통과 기준 후보

- Rule attributable regression: 없음. Fast Path 16/16 정확, Fast Path FP 0, 신규 category/result 오류 0.
- LLM_REQUIRED Provider variation: Result Accuracy 62/66 → 61/66, FP/FN 3/1 → 3/2, Injection Moderation Exact 9/10 → 9/10이 관측됐다.
  공격 지시와 정책 결과가 충돌하는 Injection Security는 4/4 → 4/4이며, injection-only SAFE 6건은
  결과만으로 공격 수행 여부를 판정할 수 없어 Security 분모에서 제외했다.
  마지막 MAJOR 수정은 Fast Path 1건을 LLM_REQUIRED로 옮긴 경계 축소이며, 나머지 LLM_REQUIRED의 Prompt/model/provider/validator는
  변경하지 않았으므로 전체 차이를 Rule 품질 저하로 단정하지 않는다.
- `RULE_QUALITY_GATE = PASS`
- `ROUTING_BENEFIT = MEASURED` (actual calls 및 total tokens 감소)
- `OVERALL_QUALITY = NO_RULE_ATTRIBUTABLE_REGRESSION_OBSERVED`

## 검증 명령

```bash
ISSUE251_AFTER=true ./gradlew :test \
  --tests 'com.bobfull.chat.adapter.Issue251ProductionRuleProviderAfterTest' \
  --rerun-tasks -PshowTestOutput
```

결과: `BUILD SUCCESSFUL`

최종 SHA 기준 일반 build는 Provider opt-in 환경 변수와 `OPENAI_API_KEY`를 제거한 다음 명령으로도 성공했다.

```bash
env -u OPENAI_API_KEY -u ISSUE251_PROVIDER -u ISSUE251_CONTEXT_V2_SPIKE -u ISSUE251_AFTER \
  ./gradlew clean build --console=plain
```

결과: `BUILD SUCCESSFUL` (Provider 평가 미실행)
