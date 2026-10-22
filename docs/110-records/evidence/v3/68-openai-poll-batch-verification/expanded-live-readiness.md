# Issue #68 — 6조건 유료 Pilot 승인 연결 무과금 검증

## 범위

이번 기록은 기존 [확장 Fake 준비](expanded-preparation.md) 이후의 **Live 승인 경로 연결 및
무과금 검증**이다. 실제 유료 Pilot 승인, 모델 품질 PASS, Batch 채택 또는 운영 활성화가 아니다.
과거 Gate V1 `BATCH_GATE_HOLD`와 기존 GT/Evidence/raw/journal/checkpoint는 보존한다.

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`.
- 브랜치: `feature/68-openai-poll-batch-verification`; 전체 #68 작업은 미커밋 상태다.
- Production 코드/설정, Prompt/Policy/Rule/Model/GT, Grafana Dashboard는 변경하지 않았다.
- 실제 OpenAI 호출 및 API Key 읽기/사용, AWS, Commit/Push/PR/Issue 변경은 하지 않았다.
- 개선 효과 주장이 아니라 전송 전 차단·이월 회계·중단 계약의 안전성 검증이다.

## 변경 경계

| 테스트 코드 | 이번 책임 |
|---|---|
| Issue68ExpandedLiveSession | 별도 유료 승인 ID/Manifest, 고정 기존 journal, 일회성 claim/잠금, 신규 checkpoint 연결 |
| Issue68Budget | 기존 장부에 append하는 `EXPANDED_LIVE_BEGIN/END`, 전체 $1 및 Pilot 신규 $0.10, 재시작 차단 |
| Issue68HttpBoundary | Pilot 전용 HTTPS factory, 동일 Payload/Usage/예약/SDK Retry 차단기, 현재 실행 단위 검증 |
| Issue68OpenAiKafkaHarness | Fake 전용 제한을 무조건 해제하지 않고 승인된 Pilot/현재 단위만 Live 연결 |
| Issue68ExpandedPilotExecution | Live와 loopback에서 동일 실제 Consumer/JPA/Owner/SDK 완료 판정 |
| Issue68ExpandedPilotLiveTest | 별도 승인 부재 시 전체 SKIP, Pilot120만 실행 가능, 본 비교/반복 자동 실행 없음 |
| Issue68ExpandedLiveSafetyTest / Issue68ExpandedLiveKafkaSafetyTest | 고정 장부와 동일한 temp fixture에서 승인·HTTPS·예산·Owner 실패 경계 검증 |

기존 Fake 경로는 여전히 temp/127.0.0.1만 허용한다. 예산 또는 전송 보호장치를 삭제하지 않았다.
기존 O0 승인 ID와 Manifest는 새 6조건 실행 권한이 아니다.
완료 이후 보고에서 단계 비용이 0으로 보이지 않도록 마지막 단계의 부담/attempt 조회를 유지했다.

## 실제 고정 장부 보존 및 예산 계약

실제 `.issue68-live/budget.jsonl`에는 이번 단계의 BEGIN/RESERVE를 추가하지 않는다.
공개된 과거 회계 6 event로 동일 SHA256의 임시 fixture를 만들고, **Live와 동일한 장부
파서·잠금·claim·예약·정산 코드**를 통과시킨다. 임시 fixture를 새 실제 예산 장부로 사용하지 않는다.

| 기존 기록 | 금액 |
|---|---:|
| UNKNOWN 예약 | $0.0192768 |
| 정산액 | $0.0003111 |
| 부담(정산 + UNKNOWN) | $0.0195879 |
| Gross 선예약 이력 | $0.0579072 |

기존 journal SHA256: `b2fe9f54ae8441010bebeb3734896b7328d5445586fd7bc1838045d3d510cf5e`.
UNKNOWN을 과거 SDK 값으로 소급 정산하지 않는다. Gross는 지출액이나 현재 부담액이 아니다.

- 전역 부담 한도 $1.00, 신규 Pilot 부담 한도 $0.10. 정산 + UNKNOWN + inflight를 포함한다.
- 각 HTTP network exchange 직전에 원자적 예약과 journal fsync를 완료한 뒤 전송한다.
- 보호 예약 = input128,000 × $0.15/1M + 실제 출력 cap × $0.60/1M.
  Native128 cap의 예약은 $0.0192768, Batch10/1280 cap은 $0.019968이다.
- Tokenizer 추정치를 금융 보호 상한으로 쓰지 않으며 Cache 할인도 보호액에서 빼지 않는다.
- 정상 HTTP200의 유효 Usage만 환급 정산한다. SDK Usage와 대조하고 불일치 시 신규 환급만
  `DISPUTED_KEEP`으로 유지한다. Batch Usage는 요청당 한 번이며 DB 행별로 나누거나 복제하지 않는다.
- Usage 불명/오류 응답은 예약 유지 및 HOLD. SDK 또는 HTTP follow-up이 생겨도 다음 전송을 막는다.
- journal 손상/빈 새 장부/다른 과거 prefix/중복 실행/기존 claim은 전송을 허용하지 않는다.
- 재시작에서는 pending/UNKNOWN 부담을 보존한다. 자동 재개하지 않고 재승인 계약이 필요하다.
- 이번 Pilot 전용 경로에서는 MAIN/REPEAT를 시작할 수 없다. 본 비교와 반복 단계는 후속 별도 승인이 필요하다.

공식 [gpt-4o-mini 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)의
128k context 및 input0.15/cached0.075/output0.60 per1M 조건을 재확인했다.
Deprecated 표시가 있으며 계정 가용성은 미검증이다. 승인 없이 모델을 교체하지 않는다.
보호 한도는 고정 단가의 이 Harness 지출 추정에 대한 것으로 세금/다른 프로세스/계정 전체 청구 상한이 아니다.

## Pilot120 최종 실행 제안 — 아직 미승인

- 승인 ID: `issue68-six-condition-pilot120-gate-v2-v1`.
- 승인 환경변수: `ISSUE68_PILOT_LIVE_APPROVAL`, `ISSUE68_PILOT_MANIFEST_SHA256`.
- Manifest 제안: [expanded-pilot-approval-manifest.json](expanded-pilot-approval-manifest.json).
  문서 작성/Manifest 생성 자체는 승인이나 Key 접근이 아니다.
- 실행 순서: Native → Poll → Size/Time0 → 20 → 50 → 100ms.
- 각 조건: 동일 진단20건, Burst1회, 합계120 판정. 기존 실험 데이터이며 신규 블라인드가 아니다.
- 3 partition/concurrency1/max.poll.records10/autoCommitFalse, Owner buffer20.
  각 단위 고유 topic/group, 격리 Testcontainers Kafka7.7.1 및 MySQL8.4의 실제 JPA 경로를 사용한다.
- Rule/Prompt/Few-shot/Policy/Model/Schema/128×실제 항목 출력 cap 유지.
- 실제 poll/Batch 크기·Case 구성·입력 순서·Payload hash·HTTP/SDK Usage·개별 DB afterCommit·Offset/Lag를 기록한다.
- Native 이외 각 조건은 실제 Batch2 이상이 있어야 완료로 인정한다. 그렇지 않으면 HOLD다.
- 정상 논리 요청 두 참고 요청형은 30~120회, HTTP480은 이론적 상한이지 목표가 아니다.
- 계획 추정 비용: 신규 $0.01452060~$0.02795310, 기존 부담 포함 $0.03410850~$0.04754100.
  이는 tokenizer/출력 cap 기반 참고치이며 실제 Usage나 완주 보장이 아니다.
- 정상 Usage를 정산하더라도 다음 전체 예약액이 남아야 다음 요청을 허용한다.
  최대 Usage/Retry/UNKNOWN/품질 오류로 조기 HOLD될 수 있으며 $0.10으로120건 완주를 보장하지 않는다.
- HTTPS는 정확히 `https://api.openai.com:443/v1/chat/completions` POST만 허용한다.
  redirect/proxy는 허용하지 않으며, 60초 transport와 직전 poll lease 65초 여유를 검사한다.
- API Key는 미래의 별도 승인 자식 프로세스 환경변수로만 전달한다. 이번 검증에서는 제거했으며
  Live 테스트가 SKIP되어 `liveCredential()`에 진입하지 않는다.
- 기존 journal을 고정 경로로 사용하고 새 승인 directory에 CREATE_NEW/fsync checkpoint를 남긴다.
  중단 시 부분 DB/Offset snapshot과 원인 class를 보존하며 Provider 오류 원문/Key는 기록하지 않는다.

## Gate V1 / V2

- 과거 V1 HOLD는 그대로 유지한다. 새 V2가 이를 소급 PASS로 바꾸지 않는다.
- 기존 GT와 별도 metadata25/14/2/39 및 hash를 유지하며 문맥 의존 사례도 원래 점수 분모에 포함한다.
- Native 일반 FP는 기준선 오류로 보존하고 같은 실행의 Batch 신규 FP와 비교한다.
- FN/검토 신호 누락/Critical 필수 Category 또는 Risk 실패/신규 FP/Category 회귀는 HOLD한다.
- 비Critical HIGH→MEDIUM은 FLAGGED·Category·검토 신호 유지 시 V2에서 분리 분석한다.
  Risk Exact와 V1 HIGH 하향 오류는 그대로 기록한다.
- Mapping/Validation/DB/Offset/Usage/예산 오류는 다음 HTTP 전에 차단한다.
- 실제 관리자 자동 검토가 보장되거나 사용자 신고가 FN을 해결한다고 해석하지 않는다.
- Fake GT echo의 기술 PASS는 모델의 V2 품질 PASS나 채택 선언이 아니다.

## 검증 결과

실행 명령, 테스트 수, 원본 해시와 보존 확인은
[expanded-live-readiness-verification.json](expanded-live-readiness-verification.json)에 별도 기록한다.

| 최종 검증 | 결과 |
|---|---|
| 신규 안전성 | 25건 PASS / 유료 전용1건 SKIP |
| 관련 회귀 | 236 tests / skipped3 / failures0 / errors0 |
| 전체 root | 1,358 tests / skipped89 / failures0 / errors0 |
| 전체 Lambda | 11 tests / skipped0 / failures0 / errors0, 실제 재실행 |
| 전체 합계 | 1,369 tests / skipped89 / failures0 / errors0 |
| test/build | SUCCESS, 6m18s, 12 tasks 모두 재실행; clean으로 기존 raw를 지우지 않음 |
| 기존 안전성 포함 | Owner14 / Batch Kafka18 / RetryProcessor12 모두 실패0 |
| 보존 | 기존 Evidence23개/private17개 및 모든 Production 파일 hash 동일; private 추가파일0 |
| 정적 검증 | JSON syntax / git diff --check / 미추적 신규 변경 파일 whitespace PASS |

최종 Manifest SHA256은
`3540e688973ad207179e1e549a05ce816ca87ab5750e46aac20e827bae12b5c1`이다.
map key 정렬 및 record 선언 property 순서에 따른 독립 Ruby 재계산이 일치했고,
최종 테스트 소스42개 및 Production 소스15개가 Manifest와 일치했다.

최종 전체 실행의 [loopback 원본](expanded-live-readiness-results.json)은
`build/issue68/expanded-live-rehearsal-1792607889982.json`과 byte hash가 같다.
SHA256: `535fe0d0ec98b90bfe83b02300e99c6ee1411220c37b6c4ed3b180dddfafdb18`.

| 조건 | logical/HTTP | 실제 요청 크기 | DB Commit | Lag/DLT |
|---|---:|---|---:|---:|
| Native | 20/20 | 1×20 | 20 | 0/0 |
| Poll | 2/2 | 10,10 | 20 | 0/0 |
| Time0 | 2/2 | 10,10 | 20 | 0/0 |
| Time20 | 2/2 | 10,10 | 20 | 0/0 |
| Time50 | 2/2 | 10,10 | 20 | 0/0 |
| Time100 | 2/2 | 10,10 | 20 | 0/0 |

모든 요청에서 HTTP/SDK Usage가 일치했다. 이 값은 **GT echo/고정 Usage Fixture** 결과로,
실제 OpenAI Token/비용/품질 또는 Size/Time 충전율 보장이 아니다.
Fixture input27,000/output2,400/total29,400, 신규 계산액$0.00549,
이월 포함 부담$0.0250779/gross$0.6431232는 temp 회계 시험값이다.
실제 고정 장부의 부담$0.0195879/gross$0.0579072는 바뀌지 않았다.

```sh
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL -u ISSUE68_O0_LIVE_APPROVAL \
  -u ISSUE68_O0_MANIFEST_SHA256 -u ISSUE68_PILOT_LIVE_APPROVAL -u ISSUE68_PILOT_MANIFEST_SHA256 \
  ./gradlew test build --rerun-tasks --no-daemon --console=plain
```

- 신규 안전성25건 PASS, 유료 전용 테스트1건 SKIP(승인 부재).
- Live URL/POST/Payload validator를 **socket 생성 없이** 검증하고, 동일 exchange 코드는 loopback SDK로 검증한다.
  실제 OpenAI TLS 연결/모델 접근/실제 분류 품질/청구는 이번 결과로 검증했다고 주장하지 않는다.
- Owner Commit 실패: 이미 성공한 DB 결과 및 미Commit Offset snapshot을 유지하고 후속 HTTP 차단.
- Usage 불명/429/오류 Usage/redirect: 첫 HTTP 뒤 예약 유지와 후속 전송 차단.
- Owner FN 주입: 이미 받은 응답의 결과는 보존하되 다음 요청과 조건은 차단.
- 예산 소진: network exchange 이전 차단으로 Fake endpoint 수신0.
- 기존 #64 Retry/DLT/Recovery-only 및 #65 lifecycle/Offset 안전성은 관련/전체 회귀에서 별도 검증한다.
  엄격한 Pilot Gate가 있는 상태에서 장애를 무시하고 DLT까지 계속 호출하는 실험은 아니다.

초기 신규 테스트 컴파일에서 package-private 테스트 helper의 교차 package 접근 오류가 있었고,
신규 통합 테스트 안의 계약 fixture 준비로 한정하여 수정한 뒤 재실행했다.
Production 코드는 수정하지 않았다.

## Human 최종 승인 요청

고정 Manifest와 기존 UNKNOWN을 포함한 전역 $1 / 신규 Pilot $0.10 계약으로,
**6조건×진단20건의 유료 Pilot120만** 실행하도록 승인할 것인지 확인한다.
승인 전에는 실행하지 않으며, 중단 후 자동 재개/본 비교1,920건/반복960건은 허용하지 않는다.
실제 OpenAI 계정 접근과 TLS/응답 오류, 품질 HOLD, 예산 조기 차단 가능성은 남아 있다.
