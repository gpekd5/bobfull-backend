# AI Moderation 트러블슈팅

## Prompt 버전 내용 불일치로 Review Actionability가 97.5%에서 65.0%로 하락한 문제

### 현상

sandbox의 실제 Prompt v2 기준선은 동일 40건에서 Review Actionability 39/40(97.5%)이었지만, BobFull 최초 통합 측정은 26/40(65.0%)이었다. Risk/Exact도 35/40에서 17/40으로 달랐다.

### 원인 확인

처음에는 모델, Spring AI의 JSON 응답 변환, 단일 run 변동을 의심했지만, BobFull이 `moderation-prompt-v2`로 저장한 실제 문자열과 sandbox에서 검증한 Prompt v2 원문이 달랐다. version 이름은 v2였지만 경계 규칙·few-shot이 누락된 축약 Prompt였으므로 Prompt 버전 내용 불일치였다.

### 해결

`ModerationPrompt`를 SYSTEM_PROMPT, `PROMPT_VERSION`, `POLICY_VERSION`의 단일 기준으로 두고, Adapter·저장 metadata가 같은 상수를 참조하게 했다. user message도 sandbox와 같이 접두어 없이 ChatMessage raw content만 전달하며, Prompt 전문과 version을 고정하는 회귀 테스트를 추가했다.

복구 후 같은 Dataset에서 Review Actionability는 39/40(97.5%), Risk/Exact는 35/40으로 돌아왔다. 이후 Prompt v3나 고정 Dataset 맞춤 튜닝은 하지 않는다. 전체 Before/After와 한계는 [#66 Evidence](../evidence/v3/66-ai-moderation/README.md)를 기준으로 한다.

### 교훈

Prompt는 단순 문자열이 아니라 version 관리되는 실행 계약이다. version 이름, 실제 본문, user input 형식, 저장 metadata가 함께 변경·검증돼야 한다.

## 모델 교체 시 OpenAI option contract 차이

### 현상과 사전 발견

모델 선정 Stage A에서 `gpt-5-nano`에 기존 `max_tokens=128` 요청을 보냈고, 실제 OpenAI가 `max_tokens`를 지원하지 않으며 `max_completion_tokens`를 사용하라는 400을 반환했다. RAW/DTO 단계 전에 발견됐으며 Provider 장애로 과장하지 않는다.

### 처리

이는 품질 탈락이 아니라 모델 기능과 실행 옵션 호환성을 확인한 결과다. 운영 gpt-4o-mini 계약을 성급히 변경하지 않고, 이번 후보는 비교에서 제외했다. gpt-5.4-nano는 Evaluation 전용 helper에서 `maxCompletionTokens(128)`와 `reasoningEffort("none")`을 사용해 Stage A와 40건 비교를 수행했다.

### 교훈

같은 Provider라도 모델 이름만 교체하면 끝나지 않는다. 모델이 지원하는 기능, 정해진 JSON 응답 형식, 실행 옵션 파라미터를 Stage A에서 먼저 실제 호출로 확인해야 한다. 상세 결과는 [#66 Evidence](../evidence/v3/66-ai-moderation/README.md)에 남긴다.

## Retry ownership 경계

Spring AI가 응답 형식 오류를 자동으로 고쳐 재시도하는 기능과 향후 Kafka retry를 중첩하면 호출 수·latency·token/cost evidence가 증폭돼 불명확해질 수 있다. #66은 Provider retry를 1회로 제한하고 실패를 retry 가능한 예외로 전파한다. 전체 작업 Retry/DLT와 최종 `ANALYSIS_FAILED` 기록은 #59 Kafka Consumer가 소유하도록 책임 계약만 확정했다. #59는 아직 구현·검증 완료가 아니다.

## 완료 결과가 늦은 최종 실패에 덮일 수 있는 경쟁 상태

### 현상

`chat_moderation.chat_message_id`는 UNIQUE이지만, 이는 중복 INSERT만 막는다. 같은 `ANALYSIS_FAILED` 행을 성공 경로와 최종 실패 경로가 각각 읽은 뒤 UPDATE하면, 먼저 저장된 SAFE/FLAGGED 완료 결과를 늦은 실패 UPDATE가 덮을 수 있다. Kafka의 중복 배달·재시도 연결 뒤에 특히 가능한 경로다.

### 해결

`ChatModeration`에 JPA `@Version`을 추가했다. 저장 시 version이 달라지면 낙관적 락 예외가 발생한다. 성공 경로는 최신 행을 재조회해 완료면 종료하고, 실패 상태일 때만 이미 확보한 AI 응답으로 DB 저장을 한 번 더 시도한다. 최종 실패 경로는 충돌 후 최신 행이 존재하면 덮어쓰지 않고 종료한다. OpenAI 호출 중 DB 락을 잡지 않으며, 충돌 처리 때문에 Provider를 다시 호출하지 않는다.

### 검증

H2/JPA 회귀 테스트에서 동일 실패 행을 별도 트랜잭션으로 두 번 읽고, 첫 번째를 FLAGGED로 저장한 뒤 두 번째 stale UPDATE를 저장했다. 늦은 저장은 `OptimisticLockingFailureException`으로 거절됐고, 최종 행은 FLAGGED로 유지됐다. 서비스 단위 테스트는 INSERT 충돌, 성공 저장 충돌 후 1회 DB 재시도, 최종 실패 충돌의 3개 정책을 검증한다.

### 교훈

UNIQUE는 행 생성 멱등성에 필요하지만, stale UPDATE의 갱신 유실을 막는 장치는 아니다. 외부 호출이 긴 작업에서는 DB 락을 호출 전체에 유지하지 말고, 짧은 저장 구간에서 version 충돌을 검출한 뒤 결과 우선순위를 명시해야 한다.
