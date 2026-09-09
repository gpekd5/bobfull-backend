# Issue #251 STEP 3B — Production CLEAR_FLAGGED Rule

## 구조

```text
ChatModerationService
  → ModerationRuleFilter
    → CLEAR_FLAGGED: Validator → ChatModeration 저장 (OpenAI 미호출)
    → LLM_REQUIRED: 기존 AiModerationPort → Validator → ChatModeration 저장
```

- `CLEAR_SAFE`는 구현하지 않았다.
- Kafka / Retry / DLT / Context / production Prompt / Frozen Dataset은 변경하지 않았다.
- LLM_REQUIRED는 원문 `ChatMessage.content`를 기존 Provider 경로에 전달한다.

## 저장 metadata

| Route | provider | model | promptVersion | policyVersion | token |
|---|---|---|---|---|---|
| CLEAR_FLAGGED | `BOBFULL_RULE` | `rule-filter-v1` | `NO_LLM` | `moderation-policy-v2` | null |
| LLM_REQUIRED | 기존 OpenAI 값 | 기존 model | `moderation-prompt-v3-scope` | `moderation-policy-v2` | Provider metadata |

`ChatModeration`의 token 컬럼은 nullable임을 확인했다. Rule 결과는 0으로 대체하지 않고 null을 저장한다.
두 route 모두 `ModerationResultValidator`를 통과한 뒤 기존 `messageId` 멱등 저장·재시도 경로를 사용한다.

## Frozen Routing Regression

- Dataset: `issue-251-hardening-v1`
- SHA-256: `9caf442202e82faaedd333ee5eaf57b422b06b48669bc7c5c1418e7487afbaba`
- CLEAR_FLAGGED / LLM_REQUIRED: 17 / 49
- Fast Path Coverage: 25.8% (17/66)
- Fast Path Precision: 1.000 (17/17)
- Fast Path False Positive: 0
- Human SAFE Fast Path: 0
- STEP 3A와 동일: 예

`LLM_REQUIRED`는 moderation SAFE 결과가 아니라 Provider 위임 route다. 그러므로 Rule 미매칭 FLAGGED Case를
Moderation False Negative로 세지 않는다.

## 검증

```bash
./gradlew :test \
  --tests 'com.bobfull.chat.service.ModerationRuleFilterTest' \
  --tests 'com.bobfull.chat.service.ChatModerationServiceTest' \
  --tests 'com.bobfull.chat.adapter.Issue251RuleRoutingSimulationTest' \
  -PshowTestOutput
```

결과: `BUILD SUCCESSFUL`

- Rule Unit: 010 phone separator, 승인된 profanity/spam, SAFE boundary, Prompt Injection 10건, 복합 위반 후보를 검증했다.
- Service Integration: CLEAR_FLAGGED는 `AiModerationPort` 0회, LLM_REQUIRED와 INJ-06은 정확히 1회 호출을 검증했다.
- Validator / ChatModeration regression: Rule 결과도 기존 Validator·저장 메타데이터·멱등성 관련 기존 테스트를 통과했다.

## 미실행

- Frozen Provider AFTER
- 비용/Token 절감 최종 측정
- Micrometer Metric
- CLEAR_SAFE
