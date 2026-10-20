# Issue #55 — Micro-batching OpenAI Smoke

## 범위와 기준

- 실행: 2026-10-20 KST (`recordedAt=2026-10-19T17:32:40.953283Z`).
- 기준 HEAD: `339423fbca943d578bf07793ff336490904290eb` + 미커밋 test source.
- Branch: `feature/55-ai-moderation-efficiency`.
- 기존 [공식 Before/Prompt 실험 Evidence](./README.md)는 수정하지 않았다.
- 이번 결과는 전체 Held-out 평가나 Before/After 비교가 아닌, 실제 Provider의 Batch 계약 수용 여부를 확인한 소규모 Smoke다.
- Production/Kafka/DB/설정은 변경하거나 기동하지 않았다.
- 실제 API는 크기별 1회, 총 3 logical calls / 3 HTTP attempts다. SDK Retry는 관측되지 않았다.
- 동일한 Seed 55 순서의 첫 Batch를 사용했으므로 2/5/10건은 중첩된다. 총 17개 결과는 고유 입력 10개에 해당한다.

## 인증 전달 확인

프로젝트 루트 `.env`의 `OPENAI_API_KEY` 할당 하나만 데이터로 읽어 Gradle 자식 프로세스의 환경변수에 전달했다.
`.env`를 source/eval하지 않았고 다른 `.env` 항목은 주입하지 않았다. 키는 명령 인수와 결과 파일에 포함하지 않았다.

Provider 호출 전 별도 테스트에서 다음 boolean만 출력했다.

```text
ISSUE55_CHILD_KEY_PRESENT=true; dotenvVariablesInjected=1
ISSUE55_TEST_PROCESS_KEY_PRESENT=true; providerCalls=0
```

키 인식 테스트의 최초 컴파일에서는 신규 Smoke 코드의 SDK 타입/Builder API 오류가 발생했다.
프로젝트의 실제 dependency API에 맞춰 테스트 코드만 보완한 뒤 키 확인 테스트가 PASS했다.
이 컴파일 보완 과정에서는 Provider 호출이 없었다.

키 전달용 wrapper는 저장소 밖 임시 경로에서 실행했고, 기존 코드/설정과 `.env`를 수정하지 않았다.
아래 Gradle 명령은 해당 wrapper가 실행한 자식 프로세스 명령이다. 키 값이나 추가 인증 인수는 없다.

```bash
./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchOpenAiSmokeTest.Gradle_테스트_프로세스에서_Key_존재만_확인한다' \
  -PshowTestOutput --console=plain

./gradlew --no-daemon :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchOpenAiSmokeTest.Batch_2_5_10을_한번씩만_호출하고_실패시_즉시_중단한다' \
  -PshowTestOutput --console=plain
```

두 테스트 실행은 각각 PASS했다. 전체 146회 비교 실험은 실행하지 않았다.

## 요청 조건과 계약

| 항목 | 값 |
|---|---|
| API | `https://api.openai.com/v1/chat/completions` |
| 설정 model | `gpt-4o-mini` |
| 응답 model | 세 호출 모두 `gpt-4o-mini-2024-07-18` |
| Prompt | `moderation-prompt-v3-short-fragment-boundary` 원문 + 테스트용 Batch 지시문 |
| Policy | `moderation-policy-v2` |
| Few-shot | 기존 15개 유지 |
| Dataset | 기존 Policy v2 Held-out 80에서 Rule 1건 / LLM 대상 79건 구분 |
| 순서 | Seed 55 shuffle의 첫 2/5/10건 |
| 입력 | `items: [{id, content}]`, Provider에는 중립 ID `m001` 등만 전달 |
| 출력 | `results: [{id, moderation: {result, categories, riskLevel}}]` |
| Schema | 기존 `ModerationResult`를 포함한 Batch envelope, native strict JSON Schema |
| Output cap | 실제 항목 수 × 128 = 256 / 640 / 1,280 |
| SDK maxRetries | 기존 기본값과 같은 3 |
| timeout | 테스트 전용 90초 |
| Usage | Batch 단위 기록. Case별 복제/임의 배분 없음 |

평가 코드가 실제 응답에 JSON Schema 검증 → ID 일대일 검증 → 기존 `ModerationResultValidator` 검증을 수행했다.
`length` 종료/불완전 응답/Parse/매핑/Validation 실패 시 다음 크기를 호출하지 않도록 했다.

## 예산 점검

예산 상한은 $0.50이다. 실행 전 보수적 예약 상한은 다음과 같다.

```text
(128,000 input tokens × 3 calls × $0.15 / 1M
 + (256 + 640 + 1,280) output cap × $0.60 / 1M)
× (최초 요청 + 최대 3 SDK retries)
= $0.2356224
```

이는 각 HTTP attempt가 모델 전체 입력 한도를 사용한다고 가정한 보수적 예산 계산이다.
실제 Usage, 실제 청구액 또는 향후 전체 비교 실험 비용이 아니다.

단가는 [OpenAI GPT-4o mini 공식 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)에
기재된 Standard input $0.15 / cached input $0.075 / output $0.60 per 1M tokens를 사용했다.
여러 메시지를 한 Chat Completions 요청에 담았으며 OpenAI 비동기 Batch API 할인은 적용하지 않았다.

## 실제 Provider 결과

| Batch | HTTP / attempts | Input | Cached input (Input에 포함) | Output | Total | Logical latency | 보수적 Estimated Cost | Cache 반영 Estimated Cost |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 2 | 200 / 1 | 993 | 0 | 51 | 1,044 | 3,095.2 ms | $0.00017955 | $0.00017955 |
| 5 | 200 / 1 | 1,064 | 0 | 127 | 1,191 | 2,207.2 ms | $0.00023580 | $0.00023580 |
| 10 | 200 / 1 | 1,164 | 1,024 | 261 | 1,425 | 3,074.2 ms | $0.00033120 | $0.00025440 |
| 합계 | 3 attempts | 3,221 | 1,024 | 439 | 3,660 | — | $0.00074655 | $0.00066975 |

- Token은 tokenizer 추정이 아닌 Provider 응답의 Usage다.
- Logical latency는 테스트에서 `.call().chatResponse()`를 포함한 평가 코드 호출 구간이다. Kafka/대기/DB를 포함한 E2E latency가 아니다.
- `microbatch-smoke.json`의 `estimatedCostUsd`는 input 전체를 일반 단가로 계산한 보수적 값이다.
- Cache 반영값은 `(input − cached) × 0.15/1M + cached × 0.075/1M + output × 0.60/1M`이다.
- 모두 공개 단가 기반 Estimated Cost이며 실제 청구액이 아니다.
- Batch 10의 cache hit와 첫 호출 준비 비용 등 때문에 이 3개 latency를 Batch 크기의 성능 효과로 해석하지 않는다.

## Schema / 매핑 / Validation

| 확인 | Batch 2 | Batch 5 | Batch 10 |
|---|---|---|---|
| 실제 Provider의 Schema 수용 | PASS | PASS | PASS |
| 결과 개수 | 2/2 | 5/5 | 10/10 |
| 누락 / 중복 / 요청 외 ID | 각각 0 | 각각 0 | 각각 0 |
| Parse / Validation 실패 | 각각 0 | 각각 0 | 각각 0 |
| 출력 잘림 / UNRESOLVED | 각각 0 | 각각 0 | 각각 0 |
| Result / Category Ground Truth 일치 | 2/2 | 5/5 | 9/10 |

계약 검증 성공과 품질 일치는 별개다. 이번 소량 응답에서 확인된 Ground Truth 차이는 다음과 같다.

| Batch | Case ID | Ground Truth | 실제 응답 | 관측 |
|---|---|---|---|---|
| 10 | `HOLDOUT-SAFE-17` | SAFE / [] / LOW | FLAGGED / [PROFANITY] / MEDIUM | False Positive 1건 |
| 10 | `HOLDOUT-PROFANITY-14` | FLAGGED / [PROFANITY] / HIGH | FLAGGED / [PROFANITY] / MEDIUM | Risk exact 차이, Review Actionability는 동일 |
| 10 | `HOLDOUT-SPAM-16` | FLAGGED / [SPAM] / HIGH | FLAGGED / [SPAM] / MEDIUM | Risk exact 차이, Review Actionability는 동일 |

Batch 2/5는 result/categories/riskLevel 모두 Ground Truth와 일치했다.
이 Smoke 표본에서는 FN이 없었다. `HOLDOUT-SAFE-17`은 기존 공식 Before mismatch 목록에도 있지만,
이번에 동일 시점 단건 호출을 재실행하지 않았으므로 새 회귀 여부를 확정하지 않는다.
현재 결과만으로 품질 유지/후보 채택/Token 절감률을 결론 내리지 않는다.

## 무과금 회귀

키를 제거하고 아래 지정 테스트만 실행했다.

```bash
env -u OPENAI_API_KEY ./gradlew :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluationTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationHeldoutDatasetTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetricsTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationPromptTest' \
  --console=plain
```

결과: 47 tests / skipped 0 / failures 0 / errors 0, PASS.
신규 코드의 `compileTestJava`도 성공했다. 전체 regression/build는 이번에 재실행하지 않았다.

## 원본 기록과 다음 단계

- [구조화 Smoke 결과](./microbatch-smoke.json): Batch Usage/model/timing/매핑 진단과 Case별 expected/actual. 원문/키 없음.
- 테스트: `src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchOpenAiSmokeTest.java`.
- 기존 Prompt 실험 README, Before 캡처, 기존 Batch/Fake 테스트 소스는 유지했다.
- Production 적용/후보 채택은 하지 않는다.
- 다음 별도 실행에서는 동일 시점 단건 79건과 Batch 2/5/10을 비교하고, Case별 새 FN/FP/Category/Actionability 회귀 및 Cache 영향을 구분해야 한다.
- 전체 비교 실험은 이번 범위에 포함하지 않았다.
