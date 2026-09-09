# ADR 0009: AI Moderation Provider 및 모델 선택

- 상태: `Accepted`
- 작성일: `2026-08-10`
- 관련 Issue·PR: #66, PR #205

## 배경

BobFull 채팅은 PROFANITY, PERSONAL_INFORMATION, SPAM과 LOW/MEDIUM/HIGH 위험도를 자체 정책으로 분류한다. LOW는 자동 통과, MEDIUM/HIGH는 관리자 검토 대상으로 연결되므로 모델 응답을 정해진 JSON 형식으로 받고 애플리케이션에서 다시 검증해야 한다. 외부 AI 분석은 채팅 저장·전달과 분리되며, 비용·latency·품질을 함께 판단해야 한다.

## 고려한 대안

1. 정규식·규칙만 사용: 설명 가능성은 높지만 현재 BobFull 분류 체계와 문맥·경계 분류를 단독으로 충족하기 어렵다.
2. OpenAI moderation 전용 API: BobFull의 분류 체계와 위험도, `ModerationResult` 응답 형식에 직접 맞추기 어렵다.
3. 범용 Chat Model + BobFull Prompt + 정해진 JSON 응답 형식: 자체 정책과 애플리케이션 검증을 유지할 수 있다.

Provider로 OpenAI와 다른 상용 LLM Provider를 고려할 수 있다. 이번 범위는 Provider 벤치마크가 아니라 BobFull 정책, 정해진 JSON 응답 형식, 애플리케이션 검증 경계를 확인하는 것이므로 Claude/Gemini 비교를 새로 수행하지 않았다. 다른 Provider가 기술적으로 불가능하다는 판단은 아니다.

## 결정

OpenAI를 단일 Provider로, production 기본 모델은 `gpt-4o-mini`로 유지한다. `AiModerationPort` 뒤의 Adapter 분리는 유지해 비용·품질·Rate Limit·장애 대응 필요가 생기면 Provider Adapter를 교체할 수 있게 한다. 멀티 Provider는 구현하지 않았다.

## 선택 이유

동일 Prompt v2와 Human-labeled 40건 Dataset에서 gpt-4o-mini와 gpt-5.4-nano는 Result/Category 100%, Review Actionability 95% 이상이라는 핵심 통과 기준을 모두 만족했다. gpt-5.4-nano의 Actionability는 1건 높았으나 Risk/Exact는 1건 낮았고, 단일 외부 LLM 실행의 차이를 결정적 품질 우위로 해석하지 않았다.

이번 실측에서 gpt-4o-mini는 더 낮은 공개 가격 기반 추정 비용과 더 낮게 관측된 avg/p95/p99 latency를 보였다. gpt-4o-mini의 `maxTokens(128)` 운영 옵션 계약도 실제 JSON 응답 변환으로 검증됐다. 상세 수치·한계는 [#66 Evidence](../../110-records/evidence/v3/66-ai-moderation/README.md)를 기준으로 한다.

`gpt-5-nano`는 Stage A에서 `max_tokens`를 거부하고 `max_completion_tokens`를 요구했다. 품질 탈락이 아니라 현재 옵션 계약과 맞지 않는지 확인한 결과이며, 이번 결정에 맞춰 운영 옵션 분기를 추가하지 않는다.

## 장점

- 현재 요구사항의 핵심 통과 기준을 만족한다.
- 이번 실측 token 기준 추정 비용이 낮다.
- 이번 BobFull 평가 환경에서 latency가 낮게 관측됐다.
- 검증된 JSON 응답 형식과 단순한 운영 옵션 계약을 유지한다.

## 단점과 위험

- 실제 관측 snapshot은 오래된 `gpt-4o-mini-2024-07-18`이다.
- 최신 모델의 향후 품질 개선을 자동으로 얻지 못한다.
- 가격·모델 동작·호출 제한은 변할 수 있고, 40건을 한 번 실행한 결과만으로 일반 성능을 보장하지 않는다.

## 검증 방법

- Prompt/version regression, Dataset 40/10/10/10 invariant, Application Validation, 전체 build/test
- opt-in 실제 Provider RAW→DTO, 대표 6건, 동일 40건 품질·token·latency 평가
- 비용은 확인일 공개 가격 × 실측 token으로만 추정

## 동시성 보완

`chat_moderation.chat_message_id`의 UNIQUE 제약은 동일 메시지의 중복 INSERT만 막는다. 이미 `ANALYSIS_FAILED` 행을 읽은 성공 경로와 최종 실패 경로가 동시에 갱신하면, UNIQUE만으로는 늦은 UPDATE가 완료 결과를 덮는 것을 막을 수 없다.

따라서 `ChatModeration`에 JPA `@Version`을 둔다. OpenAI 호출 중에는 DB 락을 보유하지 않고, 저장 시점의 version 불일치만 `OptimisticLockingFailureException`으로 검출한다. 충돌 시 정책은 다음과 같다.

- 최신 행이 SAFE/FLAGGED 완료이면 성공 결과가 이긴 것으로 보고 저장을 종료한다.
- 최신 행이 `ANALYSIS_FAILED`이면 이미 받은 AI 응답만 새 행에 적용해 DB 저장을 한 번 재시도한다. Provider를 다시 호출하지 않는다.
- 최종 실패 기록이 충돌하면 최신 행을 재조회하고, 이미 존재하는 완료·실패 행을 덮어쓰지 않고 종료한다.

이는 #59 Kafka Consumer의 retry/DLT 정책을 구현하거나 대체하지 않는다. #59는 분석 실패만 재시도하고, 분석 성공 뒤 DB 저장 충돌은 이 Core의 짧은 DB 재시도 정책으로 해소한다.

## 재검토 조건

- 품질 KPI 하락 또는 신규 held-out Dataset에서의 유의한 차이
- OpenAI 가격·Rate Limit·장애 조건 변화
- #59 Kafka Consumer throughput/E2E 병목
- 멀티모달 Moderation 요구
