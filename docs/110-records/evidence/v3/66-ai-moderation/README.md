# Issue #66 AI 채팅 Moderation Evidence

## 검증 대상

`ChatMessage` 원문을 변경하지 않고, OpenAI가 정해진 JSON 형식으로 반환한 결과를 검증해 `messageId` 기준으로 저장한다.
Kafka Consumer와 Retry/DLT는 Issue #59 범위이므로 이 Evidence에서 구현·측정하지 않는다.

## 측정 계약

- Primary KPI: 고정 Human-labeled 40건의 Review Actionability 정확도(LOW 제외, MEDIUM/HIGH 포함)
- Secondary KPI: OpenAI 호출 latency(avg/p95/p99), token usage, 오류 유형, messageId당 외부 호출 수
- 안전 확인: AI 실패가 ChatMessage 저장·전달을 변경하지 않고, 완료된 messageId의 재호출과 결과 중복 생성이 없어야 한다.

## 기준 코드

- Before SHA: `2a01065146597fbc68d51dfb0f048807d95e31f4`
- After SHA: `7e710c129fb4588706619c2a85825708c889ff72`
- Prompt 불일치 복구 SHA: `375af8f`

## Prompt·Policy·Provider

- provider: OpenAI
- model: `spring.ai.openai.chat.model` ← `OPENAI_CHAT_MODEL` 환경변수, 기본값 `gpt-4o-mini`
- moderation output token cap: `bobfull.ai.moderation.max-output-tokens` ← `OPENAI_MODERATION_MAX_TOKENS`, 기본값 `128`. Adapter가 Spring AI 2.0.0 `OpenAiChatOptions.builder().maxTokens(...)`를 Moderation 요청에만 전달한다. 전역 `spring.ai.openai.chat.max-tokens`는 설정하지 않으며 reasoning model용 `max-completion-tokens`와 함께 설정하지 않는다.
- local/prod API Key: `OPENAI_API_KEY` 환경변수. 실제 키는 `application-local.yml`·배포 설정·저장소에 저장하지 않는다.
- promptVersion: `moderation-prompt-v2`
- policyVersion: `moderation-policy-v1`
- Prompt source: `ModerationPrompt`가 `PROMPT_VERSION`, `POLICY_VERSION`, `SYSTEM_PROMPT`를 함께 관리한다. Adapter와 저장 Service는 이 버전 상수를 참조한다.
- 응답 형식: Provider가 정해진 JSON으로 응답하도록 요청한다. Spring AI가 스키마 오류를 자동 재시도하는 기능은 사용하지 않는다.
- Provider retry: `spring.ai.retry.max-attempts=1`. 전체 재시도는 #59 Kafka Consumer가 소유한다.

## 사전 sandbox validation

아래 값은 BobFull 통합 결과가 아니라 별도 sandbox에서 동일 Human-labeled 40건으로 수행한 사전 검증이다.

| 지표 | Prompt v1 | Prompt v2 |
|---|---:|---:|
| Result Accuracy | 40/40 | 40/40 |
| Category Accuracy | 40/40 | 40/40 |
| Risk Accuracy | 36/40 | 35/40 |
| Exact Match | 36/40 | 35/40 |
| Review Actionability | 37/40 | 39/40 |

Prompt v2는 Risk Exact Match를 위해 v3로 과적합하지 않고 현재 기준선으로 고정한다.

## BobFull 40건 Dataset 동기화

- 최종 기준: `demo2/src/test/java/com/example/demo/ModerationEvaluationTest.java`의 `testCases()`
- 추출 항목: case id, message content, expected result/categories/riskLevel
- BobFull 위치: `SpringAiModerationAdapterOpenAiEvaluationTest`
- 계약 검증: 별도 `SpringAiModerationEvaluationDatasetTest`가 총 40건과 SAFE/PROFANITY/PI/SPAM 각 10건을 확인한다.
- Prompt v2와 Human expected는 demo2 원본에서 변경하지 않았다.

## BobFull 통합 후 결과

| 지표·현상 | 결과 | 판정 |
|---|---:|---|
| 실제 OpenAI 단건 호출 | local IntelliJ 환경에서 Context 기동·Provider 호출·JSON 응답 변환·SAFE 기대값 확인 | PASS (Human 실행 결과) |
| 40건 고정 Dataset 재측정 (Prompt 버전 내용 불일치 발견 전 BobFull 실측) | Result 40/40, Category 40/40, Risk 17/40, Exact 17/40, Review Actionability 26/40 | BEFORE |
| Prompt 불일치 발견 전 Provider 관측 | Provider Failure 0, OpenAI Calls 40, latency avg 967.6ms / p95 1239ms / p99 3559ms, total token 14,256, total elapsed 38,723ms | BEFORE (Human 실행 결과) |
| Prompt v2 복구 baseline 40건 | Result 40/40, Category 40/40, Risk 35/40, Exact 35/40, Review Actionability 39/40 | BASELINE (Human 실행 결과) |
| Prompt v2 복구 baseline Provider 관측 | OpenAI Calls 40, latency avg 869.6ms / p95 1292ms / p99 1569ms, prompt/completion/total token 30,897 / 687 / 31,584, total elapsed 34,804ms | BASELINE (Human 실행 결과) |
| Prompt v2 + 출력 토큰 128 상한 40건 | Result 40/40, Category 40/40, Risk 34/40, Exact 34/40, Review Actionability 38/40 | 상한 적용 (Human 실행 결과) |
| Prompt v2 + 출력 토큰 128 상한 Provider 관측 | Provider/응답 형식 오류 0, OpenAI Calls 40, latency avg 1037.1ms / p95 1748ms / p99 1904ms, prompt/completion/total token 30,897 / 686 / 31,583, total elapsed 41,503ms | 상한 적용 (Human 실행 결과) |
| Core 정상·검증실패·멱등성·AI 장애가 채팅 저장으로 번지지 않는지 | `./gradlew :test --tests com.bobfull.chat.service.ChatModerationServiceTest` 성공 | PASS |
| 전체 build 및 테스트 | `./gradlew test` 성공 | PASS |

실제 단건 검증은 `OPENAI_API_KEY`가 있는 local 환경에서 다음처럼 별도로 실행한다. 이 테스트는 키가 없는 일반 build에서는 JUnit 조건으로 건너뛴다.

```bash
./gradlew :test --tests com.bobfull.chat.adapter.SpringAiModerationAdapterOpenAiEvaluationTest
```

Prompt 버전 내용 불일치 발견 전 실측의 Risk FAIL은 PROFANITY MEDIUM→LOW 4건, PROFANITY HIGH→MEDIUM 1건, PERSONAL_INFORMATION MEDIUM→LOW 10건, SPAM HIGH→MEDIUM 8건이다. Review Actionability 실패 14건은 PROFANITY MEDIUM→LOW 4건과 PERSONAL_INFORMATION MEDIUM→LOW 10건에 정확히 대응한다. Prompt 복구 후에는 먼저 `Prompt_v2_대표_회귀_6건을_실제_OpenAI로_검증한다`로 경계 5건과 SAFE 1건을 확인하고, 그 다음에만 동일 40건을 실행한다.

Prompt 복구 후 FAIL은 `PROFANITY-07`의 MEDIUM→LOW 1건과 `SPAM-02`, `SPAM-05`, `SPAM-07`, `SPAM-09`의 HIGH→MEDIUM 4건이다. PROFANITY-07만 Review Actionability에 영향을 준다. SPAM 4건은 Exact Risk와 다르지만 예상·실제 모두 MEDIUM/HIGH이므로 REVIEW_REQUIRED 운영 행동은 동일하다. After Risk/Exact 35/40 및 Review Actionability 39/40은 sandbox Prompt v2 기준선과 동일하다. 따라서 이 비교는 Prompt 버전 내용 불일치가 BobFull 품질 붕괴의 원인이었음을 뒷받침한다.

상세 boundary/few-shot 복구로 total token은 14,256에서 31,584로 증가했다. 반면 Review Actionability는 65.0%에서 97.5%로 회복됐다. latency와 총 경과 시간은 외부 OpenAI 실행 편차가 있으므로 Before 대비 성능 개선으로 주장하지 않고, 각 실행의 실제 관측값으로만 기록한다.

| After 40건 카테고리 | Result/Category | Risk/Exact | Review Actionability |
|---|---:|---:|---:|
| SAFE (10) | 10/10 | 10/10 | 10/10 |
| PROFANITY (10) | 10/10 | 9/10 | 9/10 |
| PERSONAL_INFORMATION (10) | 10/10 | 10/10 | 10/10 |
| SPAM (10) | 10/10 | 6/10 | 10/10 |

After의 Result/Category 기준 오탐·미탐은 각각 0건이다. token은 호출당 평균 prompt 772.4, completion 17.2, total 789.6이며, 이는 40회 순차 실행의 관측값이다.

40건 평가는 같은 클래스의 `Prompt_v2를_동일한_40건_Human_labeled_Dataset으로_측정한다` 테스트가 순차 호출로 수행한다. Result/Category/Risk/Exact/Review Actionability 정확도, FAIL case ID와 expected/actual, 요청별 latency의 avg/p95/p99, token 합계와 총 호출 수를 출력한다. 40건 누적 실행 시간은 참고값으로만 출력한다.

Evaluation Test는 H2와 JWT/PortOne/Mail 테스트값만 사용하고, payment·reservation scheduler 및 chat-room/email outbox background job을 모두 비활성화한다. 외부 의존은 OpenAI만 남긴다.

`실제_OpenAI_RAW와_DTO를_콘솔에서_확인한다`는 API Key가 있는 opt-in Evaluation Test에서만 ChatMessage 원문, Provider RAW, Parsed DTO, metadata/token을 stdout으로 출력한다. 운영 Logger에는 원문·RAW를 추가하지 않는다. 콘솔 출력은 다음 명령에서만 활성화한다.

```bash
./gradlew :test \
  --tests 'com.bobfull.chat.adapter.SpringAiModerationAdapterOpenAiEvaluationTest.실제_OpenAI_RAW와_DTO를_콘솔에서_확인한다' \
  --rerun-tasks \
  -PshowTestOutput
```

## 출력 토큰 상한 검증

`OPENAI_MODERATION_MAX_TOKENS=128`은 Prompt v2 기준선 측정 뒤에 추가한 Moderation 전용 Provider request option이다. 기존 baseline 수치를 유지한 채 다음 검증을 수행했다.

### RAW → DTO 실제 Provider 확인

Human local opt-in test 결과는 다음과 같다. 이 출력은 학습·검증용 test stdout에만 허용하며 운영 Logger에는 원문·RAW를 기록하지 않는다.

```text
INPUT: 내 번호 010-1234-5678이야
OPENAI RAW: {"categories":["PERSONAL_INFORMATION"],"result":"FLAGGED","riskLevel":"MEDIUM"}
PARSED DTO: ModerationResult[result=FLAGGED, categories=[PERSONAL_INFORMATION], riskLevel=MEDIUM]
METADATA: provider=OpenAI, model=gpt-4o-mini-2024-07-18, promptTokens=775, completionTokens=19, totalTokens=794
```

Provider가 반환한 JSON은 `ModerationResult`로 정상 변환됐고, completion 19는 128 상한보다 충분히 낮다. 상한 도달 또는 잘림의 근거는 없다.

### 대표 6건 회귀

`Prompt_v2_대표_회귀_6건을_실제_OpenAI로_검증한다`는 실제 OpenAI 호출에서 6/6 PASS했다. PROFANITY MEDIUM 2건, PERSONAL_INFORMATION MEDIUM 2건, SPAM HIGH 1건, SAFE LOW 1건이 모두 기대값과 일치했다.

### Prompt v2 + 출력 토큰 128 상한 40건 검증

| 지표 | Prompt v2 복구 baseline | Prompt v2 + 출력 토큰 128 상한 |
|---|---:|---:|
| Result Accuracy | 40/40 (100.0%) | 40/40 (100.0%) |
| Category Accuracy | 40/40 (100.0%) | 40/40 (100.0%) |
| Risk / Exact | 35/40 (87.5%) | 34/40 (85.0%) |
| Review Actionability | 39/40 (97.5%) | 38/40 (95.0%) |
| Provider 오류 / 응답 형식 오류 | 0 / 0 | 0 / 0 |
| latency avg / p95 / p99 | 869.6 / 1292 / 1569ms | 1037.1 / 1748 / 1904ms |
| prompt / completion / total token | 30,897 / 687 / 31,584 | 30,897 / 686 / 31,583 |

출력 토큰 상한 적용 실행의 불일치는 `PROFANITY-07` MEDIUM→LOW, `PI-09` MEDIUM→LOW, `SPAM-02`, `SPAM-05`, `SPAM-07`, `SPAM-09` HIGH→MEDIUM이다. PI-09의 LOW 변동으로 Review Actionability가 baseline보다 1건 감소했다.

### 해석과 Freeze

- 출력 토큰 상한 적용 실행의 평균 completion은 686/40 ≈ 17.15 tokens이며, 128 상한 도달·잘림·parse 실패는 0건이다. 따라서 현재 Moderation JSON 응답에는 128 상한이 충분한 여유를 둔다.
- 같은 Prompt/Dataset/모델 계열의 단일 외부 LLM 실행 간 차이를 출력 토큰 128 상한의 인과적 품질 저하로 해석하지 않는다. latency 차이도 출력 토큰 상한에 의한 성능 저하라고 주장하지 않는다.
- Prompt v2는 고정한다. 동일 Dataset에 맞춘 Prompt v3, PI-09/PROFANITY-07/SPAM case 맞춤 few-shot, expected 변경을 하지 않는다. 향후 품질 개선은 별도 신규 Human-labeled·versioned held-out Dataset에서 재평가한다.

## 정합성·장애 영향 분리

- `chat_moderation.chat_message_id`는 UNIQUE다.
- UNIQUE는 중복 INSERT만 막으므로 `ChatModeration.version`의 JPA 낙관적 락으로 stale UPDATE도 막는다. 충돌 시 완료 상태가 최신이면 종료하고, 최신이 `ANALYSIS_FAILED`일 때만 이미 받은 AI 응답으로 DB 저장을 1회 재시도한다.
- 2026-08-11 H2/JPA 회귀: 같은 실패 행을 별도 트랜잭션에서 2회 읽고 첫 저장을 FLAGGED로 완료한 뒤 늦은 stale UPDATE를 시도했다. 늦은 저장은 `OptimisticLockingFailureException`으로 거절됐고 최종 상태는 FLAGGED였다. 서비스 정책 테스트 3건(INSERT 충돌, 성공 저장 충돌 후 1회 DB 재시도, 최종 실패 충돌)도 PASS했으며, 충돌 처리 중 Provider 재호출은 0회다.
- SAFE/FLAGGED 완료 결과가 있으면 AI를 재호출하지 않는다.
- 외부 AI 호출은 ChatMessage 조회·결과 저장의 짧은 DB 작업 사이에서 수행한다.
- Provider/구조 검증 실패는 저장 없이 예외를 전파한다. #59 Consumer가 Retry/DLT를 소유하며, Retry 소진 뒤에만 `recordFinalFailure`로 `ANALYSIS_FAILED`를 기록한다.
- ChatMessage.content를 수정·가림·삭제하지 않는다.

## 로그·메트릭

구조화 로그는 `messageId`, result/categories/riskLevel, latency, errorCode만 남긴다. ChatMessage 원문, Prompt, Completion, 개인정보, API Key는 로그·메트릭에 기록하지 않는다. messageId는 Micrometer label로 사용하지 않는다.

## Known Limitation

- sandbox v2에서 `이런 젠장`은 Human MEDIUM 기대와 달리 LOW였다. SPAM 4건은 HIGH/MEDIUM 차이지만 모두 REVIEW_REQUIRED 산정 대상이다.
- demo2가 Prompt v2를 `BobFull Moderation Policy v2`로 표기한 것은 promptVersion과 policyVersion을 혼용한 과거 sandbox 명명 오류다. BobFull은 Prompt 원문 경계·few-shot·출력 규칙을 복구하되, 공식 계약인 `moderation-prompt-v2`와 `moderation-policy-v1`을 유지한다.
- Prompt v2는 이 Dataset에 대해 추가 튜닝하거나 `moderation-prompt-v3`를 만들지 않는다. 현재 After 결과를 Prompt v2 기준선으로 동결한다.
- #59가 아직 `status:draft`이므로 Kafka Consumer/Retry/DLT 연결은 구현하지 않았다.
- Prompt injection·탐지 회피 입력은 시스템 프롬프트의 "분석 대상 데이터" 지시와 정해진 JSON 응답 범위로만 완화한다. 악의적 회피를 별도 탐지·차단하는 기능은 현재 #66 범위에 없다.

## OpenAI Model Selection

### 배경과 고정 기준

Human은 실제 Provider metadata에서 `gpt-4o-mini-2024-07-18` snapshot을 확인한 뒤, 2026년 기준 더 적합한 소형 모델이 있는지 재검토했다. 최신 모델이라는 이유만으로 교체하지 않고, frozen `moderation-prompt-v2` / `moderation-policy-v1`과 같은 Human-labeled 40건 Dataset으로 비교했다. Dataset·expected와 Prompt는 이 비교를 보고 변경하지 않았다.

핵심 통과 기준은 Result Accuracy 100%, Category Accuracy 100%, Review Actionability 95% 이상, Provider 오류 0, 응답 형식·변환 오류 0이다. Risk/Exact, 공개 가격 기반 추정 비용, latency, token usage는 Secondary 지표다. LOW와 MEDIUM/HIGH의 관리자 검토 분기가 실제 운영 행동이므로 Exact Risk보다 Review Actionability를 우선한다.

### 후보와 API 옵션 호환성

- `gpt-4o-mini`: production 기본값이며 `maxTokens(128)` → Chat Completions `max_tokens` 계약을 사용한다. 실제 metadata snapshot은 `gpt-4o-mini-2024-07-18`이었지만 alias를 snapshot으로 강제 pin하지 않는다.
- `gpt-5-nano`: Stage A RAW 호출에서 실제 OpenAI가 `400 Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.`라고 응답했다. 품질 탈락이 아니라 현재 운영 옵션 계약과 맞지 않아 이번 비교 범위에서 제외했다. 운영 옵션 구조를 확장하지 않았다.
- `gpt-5.4-nano`: 공식 OpenAI 문서가 분류, 데이터 추출, 순위화 같은 대량의 단순 작업을 대상으로 안내하고 Chat Completions에서 정해진 응답 형식과 `reasoning.effort=none`을 지원한다고 설명한다. Evaluation 전용 helper에서만 `maxCompletionTokens(128)` / `reasoningEffort("none")`을 적용했고 production Adapter는 변경하지 않았다. [GPT-5.4 nano 공식 문서](https://developers.openai.com/api/docs/models/gpt-5.4-nano)

### Stage A

`gpt-5.4-nano` RAW 단건은 실제 Provider에서 성공했다. `PERSONAL_INFORMATION` / `MEDIUM` JSON이 `ModerationResult`로 변환됐고, snapshot은 `gpt-5.4-nano-2026-03-17`, prompt/completion/total token은 773 / 26 / 799였다. `maxCompletionTokens=128`, `reasoningEffort=none`에서 응답 잘림·parse failure는 없었다. 같은 계약의 대표 6건은 6/6 PASS했다.

### 동일 40건 비교

| Model | 실행 조건 | Result | Category | Risk / Exact | Review Actionability | Provider 오류 / 변환 실패 | avg / p95 / p99 latency | Prompt / Completion / Total tokens | 공개 가격 기반 40건 추정 비용 |
|---|---|---:|---:|---:|---:|---:|---|---|---:|
| gpt-4o-mini | 128 상한 | 40/40 | 40/40 | 34/40 | 38/40 (95.0%) | 0 / 0 | 1037.1 / 1748 / 1904ms | 30,897 / 686 / 31,583 | $0.005046 |
| gpt-5.4-nano | evaluation | 40/40 | 40/40 | 33/40 | 39/40 (97.5%) | 0 / 0 | 1686.6 / 5360 / 5838ms | 30,817 / 969 / 31,786 | $0.007375 |

gpt-5.4-nano mismatch는 `PROFANITY-07` MEDIUM→LOW 및 `SPAM-02`, `SPAM-03`, `SPAM-05`, `SPAM-07`, `SPAM-09`, `SPAM-10` HIGH→MEDIUM이다. 두 모델 모두 핵심 통과 기준을 만족했다.

### 가격과 해석

가격 확인일은 2026-08-10이며, OpenAI 공개 text token 단가인 gpt-4o-mini input/output $0.15/$0.60, gpt-5.4-nano $0.20/$1.25 per 1M tokens를 적용했다. [GPT-4o mini 공식 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini), [GPT-5.4 nano 공식 문서](https://developers.openai.com/api/docs/models/gpt-5.4-nano)

| Model | 40건 | 메시지 1건 | 100,000건 | 1,000,000건 |
|---|---:|---:|---:|---:|
| gpt-4o-mini | $0.005046 | $0.0001262 | $12.62 | $126.15 |
| gpt-5.4-nano | $0.007375 | $0.0001844 | $18.44 | $184.37 |

이는 실제 청구액이 아니라 공개 token 단가 × 이 평가의 실측 token 사용량이다. cached input, Batch, 계약 조건과 가격 변경에 따라 실제 billing은 달라질 수 있다. 이번 측정 기준 gpt-5.4-nano 추정 비용은 약 46% 높다.

gpt-5.4-nano의 Review Actionability는 1건 높았지만 Risk/Exact는 gpt-4o-mini가 1건 높았다. 외부 API 40건 단일 실행의 1건 차이를 결정적 품질 우위로 해석하지 않는다. 이번 BobFull 40건 평가 환경에서는 gpt-5.4-nano의 avg/p95/p99 latency가 더 높게 관측됐지만, 이를 모든 환경에서 항상 더 느리다고 일반화하지 않는다.

### 최종 Human 결정

두 모델은 모두 핵심 통과 기준을 만족했다. Human은 gpt-5.4-nano의 결정적 품질 우위가 확인되지 않았고, 이번 실측에서 gpt-4o-mini가 더 낮은 공개 단가와 latency를 보였으므로 production 기본 모델을 `gpt-4o-mini`로 유지하기로 결정했다. 최신 모델이라는 이유만으로 교체하지 않았으며, 향후 재평가는 신규 Human-labeled/versioned held-out Dataset에서 수행한다.

Kafka Consumer가 구현된 뒤 #59는 `ChatMessage COMMIT → Outbox → Kafka → Consumer → OpenAI → ChatModeration 저장`의 E2E latency, OpenAI latency, throughput, Consumer Lag, Retry/DLT, backlog recovery를 별도 측정한다. 이는 아직 측정·구현 완료가 아니다.

## 관련

- Issue: #66
- 선행/연결: #59
