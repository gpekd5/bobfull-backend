# Issue #68 — Gate V2 승인 조건 및 O0 HTTP 안전장치

## 범위와 현재 상태

기준 HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`, branch
`feature/68-openai-poll-batch-verification`의 미커밋 테스트 전용 보완이다.
이 문서는 앞선 [O0 준비 기록](o0-readiness.md)을 덮어쓰지 않는 후속 승인 계약이다.
앞선 Native FP 즉시 HOLD 설명은 당시 계약이며, 아래 실행 정책으로 변경된 부분을 구분한다.
과거 모델 응답/점수/Gate V1 `BATCH_GATE_HOLD`/장부는 소급 변경하지 않는다.
실제 OpenAI 호출·API Key 읽기·Production/GT/Prompt/Policy/Rule/Model 변경은 이번 단계에 없다.
Batch Production 기본 OFF, O0는 **별도 Human 유료 실행 승인 대기**다.

## Metadata 동결 점검

[전체80건 원래 metadata](gate-v2-metadata-v1.json)의 Case ID·본문 hash·분류·근거를 재확인했다.
기존 분류와 hash를 변경할 필요는 발견되지 않았다. 이 목록을 기존 GT와 별도 평가 metadata로 동결한다.

| 분류 | Case ID (모두 HOLDOUT- 접두사) | 표현/Policy 근거 |
|---|---|---|
| Critical25 | PROFANITY-02,08,09,10,14,15; PI-01~16; SPAM-08~10 | 명시적 강한 욕설/모욕, Human 엄격 보호로 지정한 보복·추적 표현, 직접 개인 연락/식별 정보, 고위험 금전 유도 |
| 문맥 의존14 | SAFE-16,17,19,20; PROFANITY-05,06,07,12,13,16,17,18,19,20 | 경미한 평가·호칭·농담·칭찬/비유·불명확한 위해 예고. 강한 위해 행위의 명시성과 구분 |
| 위험도 경계2 | SPAM-11,16 | Human이 지정한 금전적 위험의 경계. 기존 SPAM/HIGH 자체는 보존 |
| 일반39 | SAFE-01~15,18; PROFANITY-01,03,04,11; PI-17~20; SPAM-01~07,12~15,17~20 | 기존 정상 대화/직접 모욕/공개 사업장·일반 숫자/일반 상업 유인 및 정상 추천·후기 경계 |

- 원래 Policy v2 GT hash: `2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b`.
- Metadata version: `issue68-policy-v2-review-metadata-1`.
- Metadata canonical hash: `a3a424323e5585da1b9a611281ac37174c60c4b946987e541c44ad5981cd40b5`.
- Metadata hash는 원래 GT hash·각 메시지 본문 hash·profile·근거·필수 Category·최소 Risk를 함께 포함한다.
- PI Critical 최소Risk는 원래 MEDIUM, PROFANITY-09/10도 원래 MEDIUM이다. 새 HIGH 라벨을 만들지 않는다.
- PROFANITY-14/15는 이번 Human의 엄격 보호 판단을 그대로 반영한다.
  PROFANITY-16은 문장에 구체적 위해 행위가 명시되지 않았다는 기준이다.
  Metadata 생성 코드는 과거 응답/Evidence/정답률을 입력으로 읽지 않는다.
- Context14도 기존 Result/Category/Risk/FP/FN 및 검토 대상 Recall의 분모에서 제외하지 않는다.
  Context FN·SAFE/LOW 검토 누락은 비차단 예외가 아니다.
- 과거 결과를 알고 마련한 metadata라는 한계는 유지한다. 신규 블라인드 평가라고 주장하지 않는다.
- 추가로 분류가 불명확하여 Human 판단이 필요한 Case는 발견하지 않았다. GT 전체의 새 재심사를 완료했다는 뜻은 아니다.

## Native FP 및 Gate V2 실행 계약

기존 `Issue68GateV2.evaluate()`와 V1 분석 결과는 그대로 남긴다.
새 `executionBlockers()`는 실제 실행을 막는 사유만 분리하며,
실행 버전은 `issue68-service-gate-v2-execution-2`다.

| 관측 | 실행 정책 | 보존할 평가 |
|---|---|---|
| Native 일반 FP | Native80/Poll80 비교 계속 | FP, Result/Category/Risk 오차, Review Target 및 V1/V2 분석 사유 |
| Native/Both 동일 FP | 공통 오류로 기록, 정상 품질로 바꾸지 않음 | COMMON_FP 및 원래 GT 점수 |
| Batch 신규 FP | 다음 HTTP 차단 | NEW_FP/RESULT_REGRESSION/Category 회귀 |
| 공통 FP이나 Batch의 Category/Risk 조합도 변경 | 보수적으로 HOLD | COMMON_FP_CHANGED_REQUIRES_REVIEW; 공통 FP와 새로운 변화를 함께 기록 |
| Native 또는 Batch FN·검토 대상 누락 | Context 여부와 무관하게 다음 HTTP 차단 | 기존 FN/Review Target miss 모두 유지 |
| Critical 필수 Category 손실/최소 Risk 하향 | 중단 | Critical 보호 오류 및 Risk Exact |
| 비Critical HIGH→MEDIUM | FLAGGED·Category·DB 검토 신호 유지 때만 비차단 참고 | 원래 Risk 오답 및 V1 HIGH 하향 |
| 정책 위반/그 외 Category·Risk mismatch | 기존 보수적 HOLD 유지 | 오류 삭제 없음 |
| Mapping/Validation/DB/Offset/Usage/모델/예산 오류 | 기술 오류로 중단 | 미완료·UNRESOLVED/부분 상태 보존 |

관리자 검토 신호는 DB에 저장된 FLAGGED + MEDIUM/HIGH 조건이다.
실제 관리자의 자동 검토·제재가 보장된다는 의미가 아니다. 사용자 신고도 FN의 완전한 보완 수단으로 가정하지 않는다.

테스트 Harness의 실제 JPA `afterCommit`에서 실행 Gate를 관측한다.
HOLD가 생긴 경우 이미 받은 응답의 남은 결과 저장을 임의 rollback하지 않지만 다음 HTTP는 차단한다.
Consumer 종료는 소유 테스트 Thread에서 처리하며 DB/Offset/Lag 부분 checkpoint를 남긴다.
DB 저장/트랜잭션 위반과 Commit/poll 오류의 기존 ErrorHandler 통지도 관측해 후속 HTTP를 막는다.
Handler는 기존 구현에 그대로 위임하며 ACK·Retry/DLT 정책을 교체하지 않는다.
오류 통지 테스트는 실제 Handler Bean에 CommitFailedException을 주입한 것이며,
실제 broker의 wire-level Commit 실패나 재균형 장애를 재현했다고 주장하지 않는다.
중단한 후 이미 시작한 요청을 취소해 비용을 없앨 수 있다고 주장하지 않는다.
동일 80건·Seed55·Rule1/LLM79·동일3 partition 입력이며 cross-partition poll 구성은 실제 관측값을 기록한다.

## O0 승인 연결과 HTTP 경계

`Issue68O0Session`은 별도 승인 ID와 **현재 source/요청 계약 manifest SHA256**가 모두 주어져야 열린다.
현재 단계에서는 승인 환경변수를 주입하지 않으며 Live JUnit은 SKIP한다.
과거 Smoke 승인/일회성 claim으로 O0를 실행하거나 실행 범위를 넓힐 수 없다.

1. Public 실행 경로는 현재 repository의 고정 `.issue68-live/budget.jsonl`만 선택한다.
   임의 journal 경로를 받는 constructor는 temp fixture 검증용 package 경계다.
2. 정확한 기존 journal hash·3회 attempt·기존 부담/gross·과거 approval claim을 확인한다.
   동일 OS FileLock 아래 신규 one-shot claim·승인 manifest·단계 marker를 추가한다.
3. Manifest는 source hash, GT/metadata/request schema/Prompt hash, 모델·출력cap·예산·호출상한·중단 계약을 묶는다.
   실제 요청 직전에도 승인한 manifest hash와 현재 source를 대조한다.
4. API Key는 위 검증을 통과한 Context에서만 향후 승인된 자식 프로세스 환경변수로 읽는다.
   코드에 값을 저장/출력하지 않는다. 이번 무과금 검증에서는 해당 읽기 경로를 실행하지 않는다.
5. 정확한 `https://api.openai.com/v1/chat/completions` POST만 허용한다.
   proxy·redirect를 허용하지 않고 Scheme/host/port/path/query 및 실제 전송 body를 검사한다.
6. System/사용자 합성 메시지·중립ID·Schema·Model·cap(128×항목수)·옵션 허용 목록을 SDK Retry마다 확인한다.
   각 network exchange 직전 다시 같은 검사를 수행한 후 **독립 Ticket을 원자적 예약/fsync**한다.
7. SDK Retry도 같은 network 경계를 통과한다. Kafka 재전달의 이미 사용한 Case ID는 논리 요청 guard가 차단한다.
   Kafka Retry/DLT 정책을 변경한 것이 아니라 이 실험의 추가 유료 전송을 막는 것이다.
8. 정상 HTTP Usage만 정산한다. 불명/손상/연결 실패/중단 예약은 유지한다.
   SDK와 HTTP Usage가 충돌하면 먼저 환급한 차액도 `DISPUTED_KEEP`으로 다시 유지하고 후속 요청을 막는다.
9. Checkpoint는 순번별 CREATE_NEW/fsync이며 과거 checkpoint를 덮어쓰지 않는다.
   재시작은 회계 audit만 허용한다. 동일 approval로 전송을 자동 재개하지 않는다.
10. `Issue68O0LiveTest`는 이 Session과 기존 Consumer/Adapter/JPA/Kafka Harness만 연결한다.
    첫 Native80/Poll80 이후 종료하며 O1/전체880건을 실행하는 루프는 없다.

HTTP 실제 TLS 연결·계정 모델 접근·외부 청구는 이번 단계에서 미검증이다.
HTTPS 검사는 socket 전송 없이 실제 Request 객체를 검증했고,
동일 장부/예약/SDK 처리 경계의 network 동작은 loopback Fake로 검증한다.
내부 회계 검증을 실제 OpenAI 전송 성공으로 표현하지 않는다.
Gate·manifest hash·checkpoint 관측 오버헤드도 Harness에 포함된다. Fake 지연을 실제 Provider/운영 지연으로 일반화하지 않는다.

## 예산과 실행 가능성

- O0 신규 정산 + 신규 UNKNOWN + inflight 합계 상한: **$0.10**.
- 전체 #68 부담 상한: **$0.50**. 기존 UNKNOWN **$0.0192768**, 정산 **$0.0003111**,
  부담 **$0.0195879**, gross **$0.0579072**는 그대로 이월한다.
- gross는 선예약 누적 이력이며 환급 후 부담 또는 실제 지출과 다르다.
- 각 전송 예약은 **128,000 input token × uncached 단가 + 실제 payload output cap × output 단가**다.
  Tokenizer estimate를 실청구량 또는 안전한 개별 요청의 확정 상한으로 사용하지 않는다.
- Native cap128 예약 $0.0192768, Batch10 cap1280 예약 $0.019968.
  유효한 Usage로 정산하면 사용하지 않은 예약 차액만 환급한다. Cache 할인은 보호액을 낮추는 데 사용하지 않는다.
- 정상 논리 호출 Native79 + Poll8~79 = **87~158회**. HTTP632회는 158×현재 Spring AI maxRetries3의 이론 상한이며 목표가 아니다.
  실제 예산/중단 guard 때문에 그보다 먼저 막힐 수 있다.
- 기존 동결 tokenizer/cap 가정의 첫160건 계획치는 약 **$0.02623005~$0.03682290**다.
  실제 Usage가 아니라 추정치이며 Retry/불명 Usage 미포함이다. 이 정상 가정에서는 다음 전송 예약 여유도 포함해 신규0.10 안에 실행 가능하다.
- Usage 불명 Batch10 다섯 번이면 **$0.09984**가 남아 여섯 번째는 HTTP 전에 차단된다.
  이미 일부 정산액이 있으면 그보다 먼저 막힐 수 있다. 모든632회 또는160건 완주는 보장하지 않는다.
- 3xx/429/5xx/모델 접근 불가 등 유효 Usage가 없는 응답도 예약을 자동 해제하지 않는다.
- 예산은 고정 단가·모델 context 상한을 전제로 한 내부 token 비용 보호다.
  계정의 다른 프로세스 사용, 세금/환전, 미래 가격 변경, journal/claim 전체를 고의 삭제하는 공격까지 보장하지 않는다.

### 공식 자료와 현재 dependency의 구분

[공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)에서
context128k와 input $0.15 / cached input $0.075 / output $0.60 per1M을 재확인했다.
현재 문서는 Deprecated 표시도 포함하므로 실제 계정의 접근 가능성을 입증한 것으로 해석하지 않는다.
승인 없이 모델을 교체하지 않으며 접근 오류 시 중단한다.

[공식 Java SDK 문서](https://developers.openai.com/api/reference/java)의 일반 기본 Retry 설명과 달리,
현재 프로젝트의 Spring AI2.0.0 `AbstractOpenAiProperties.DEFAULT_MAX_RETRIES=3` 및
기존 실제 Context 테스트가 설정하는 maxRetries3을 사용한다. Retry 옵션은 변경하지 않았다.

## 검증과 인계

정확한 최종 건수·명령·source hash·보존 대조는 [이번 단계 검증 JSON](o0-approval-safety-verification.json)을 기준으로 한다.
중간 targeted 명령의 root 테스트는 통과했지만 unqualified `test --tests`가 Lambda에도 적용돼
`No tests found`로 실패한 실행1회가 있었다. 이후 `:test`로 바로잡았으며 실패 이력은 숨기지 않는다.

이번 단계의 새로운 Fake FP는 Gate 동작을 검증하기 위한 출력 주입이지 새 모델 품질 결과가 아니다.
기존 Fake 리허설 원본 추출·모든 Live 실패·Human GT·journal·claim/checkpoint는 소급 변경하지 않는다.
최종 안전 검증을 통과해도 **다음 O0 유료 실행은 별도 Human 승인이 필요**하다.

### 최종 확인 결과

- Gate/O0 핵심 **34 tests / failures0 / errors0**.
- 관련 **135 tests / 2 skipped / failures0 / errors0**. 유료 Smoke와 O0 Live는 모두 SKIP했다.
- 전체 root1,295 + Lambda11 = **1,306 tests / 88 skipped / failures0 / errors0**.
- 최종 `env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL -u ISSUE68_O0_LIVE_APPROVAL -u ISSUE68_O0_MANIFEST_SHA256 ./gradlew test build --console=plain --no-daemon`
  → **BUILD SUCCESSFUL in 4m23s**. root 전체 재실행, 변경 없는 Lambda11은 직전 성공 실행에서 UP-TO-DATE.
- Native FP 주입 Fake 실험: Native/Poll 각각80 DB 완료, 원래 GT 점수79/80·FP1을 그대로 보존했다.
  Native79 HTTP, Poll8 HTTP(10건×7 +9건×1), 최종 Lag/DLT/조기 Commit 위반0이었다.
  해당 주입 Case는 HOLDOUT-SAFE-10이며 GT 또는 실제 모델을 바꾼 결과가 아니다.
- DB 실패 이후 Fake HTTP1회에서 차단, Commit 오류 통지 이후 Fake HTTP0회,
  잔여 예산 부족 시 Fake endpoint 수신0회를 확인했다. 기존 회귀와 별도의 무과금 결함 주입이다.
- O0 Live claim/승인 directory/결과 파일이 실제 private root에 생성되지 않았음을 확인했다.
- 작업 시작 시 기존 #68 Evidence18개와 private 파일17개는 모두 byte hash 동일하다.
  변경은 테스트 전용 기존9개·신규3개 및 이번 단계 Evidence2개다.
- 최종 승인 manifest canonical SHA256:
  `8b846948b2d3dc1453749041744de8423e82a5898c3ffb90bb6986fcacff86a9`.
  독립 JSON canonical hash 및 현재 test source29개 hash가 일치한다.
  문서 자체/미래 Commit SHA를 manifest에 순환 삽입하지 않는다.

동결 metadata와 신규 부담0.10/전체0.50 계약의 무과금 조건은 충족했다.
현재 manifest의 **O0 첫 Native80/Poll80만** 다음 유료 승인 대상으로 제출한다.
실제 HTTPS·계정 모델 가용성·Provider Usage·품질/지연/비용 결과는 아직 실행·확정하지 않았다.
