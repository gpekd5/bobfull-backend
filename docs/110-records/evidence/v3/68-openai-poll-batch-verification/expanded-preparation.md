# Issue #68 — 6조건 확장 Matrix 무과금 준비

## 범위와 계약

Human이 승인한 **테스트 전용 확장과 무과금 검증** 기록이다. 유료 Pilot, 전체 Matrix,
Production 활성화 승인이 아니다. 기존 Gate V1 `BATCH_GATE_HOLD`와 모든 과거 평가·장부는 보존한다.

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`.
- 브랜치: `feature/68-openai-poll-batch-verification`; 이번 소스도 미커밋 상태다.
- 원본 GT/metadata는 [기존 동결 기록](o0-approval-safety.md)을 사용한다.
  Critical25 / 문맥의존14 / 위험도경계2 / 일반39는 변경하지 않았다.
- Policy v2 Held-out80, 진단20은 역사적 평가 데이터이며 신규 블라인드 표본이 아니다.
  Human GT 미확정 신규40건은 포함하지 않는다.
- Production 코드/설정, Prompt/Few-shot/Policy/Rule/Model, Batch 기본 OFF를 변경하지 않았다.
- 실제 OpenAI 호출 및 API Key 사용/읽기, AWS 작업, Commit/Push/PR는 수행하지 않았다.

## 측정 계약

- Primary: 기존 안전 경계를 6조건에 연결하고, 오류 발생 이후 **추가 HTTP 전송 차단** 및 부분 상태 보존 확인.
- Secondary: 동일 입력/3partition/발행 방식, 요청별 실제 구성·Usage와 SDK 대조,
  수신·발행 ACK→개별 JPA afterCommit, 최종 Lag/Offset/DB, Buffer 관측 연결.
- 안전 확인: 기존 O0/Retry/Recovery/Owner 회귀, 기존 journal·Evidence hash 보존.

## 확장 Matrix와 Pilot

| 단계 | 구성 | 평가 건수 | 정상 논리 요청의 두 참고 요청형 | 신규 부담 한도 | HTTP 전송 상한 |
|---|---|---:|---:|---:|---:|
| Pilot | 진단20 × 6조건 × Burst1회 | 120 | 30~120 | $0.10 | 480 |
| 본 비교 | Held-out80 × 6조건 × 4패턴1회 | 1,920 | 476~1,896 | $0.50 | 7,584 |
| 조건부 반복 | Held-out80 × 6조건 × Burst추가2회 | 960 | 238~948 | $0.25 | 3,792 |

- 조건: Native, Poll, Owner Time0/20/50/100ms. Native 기준선을 먼저 완료하고 나머지 조건 순서를 회전한다.
- 트래픽 목표: low250ms / interval40ms / burst0ms / short5ms.
- Pilot 순서: Native → Poll → Time0 → Time20 → Time50 → Time100.
- 전체80은 Rule1/LLM79, Pilot20은 LLM20이다. Output cap은 실제 항목 수 ×128, Native128이다.
- Dataset seed55는 입력 순서용이며 Provider sampling seed를 새로 설정하지 않는다.
- 매 실행 단위에 고유 topic/group과 격리 DB 상태를 사용한다. 중복 ID는 같은 실행 단위에서 거절한다.
- Context의 모든 수신 partition 할당 완료 후 생산을 시작한다. DB 완료를 기다리지 않으며
  단조 시각의 고정 발행 일정과 실제 publish/ACK를 함께 기록한다.
- Poll/Owner가 실제로 묶은 Case ID·중립 ID 순서·Payload hash·cap을 기록한다. 예상 Batch를 강제로 품질 결과처럼 만들지 않는다.
- Pilot의 Native 이외 조건은 실제 Batch2 이상을 적어도 한 번 확인해야 완료할 수 있다.
- 단위 완료에는 DB 결과뿐 아니라 Lag/Offset/DLT/Usage의 기술 검증도 필요하다.
  한 단계가 끝나도 Session이 다음 단계를 자동 시작하지 않는다.

## 테스트 전용 구현

| 코드 | 책임 |
|---|---|
| Issue68ExpandedPlan | Matrix, 동일 GT/metadata, 단위 순서, Case 중복/매핑, Native 기준선과 V1/V2 평가 |
| Issue68ExecutionGate | 기존 O0와 확장 계획이 동일 HTTP/Usage 경계를 재사용하는 작은 테스트 interface |
| Issue68ExpandedPreparationSession | temp fixture 잠금/장부 연결, source manifest, append-only checkpoint, 명시적 단계 완료 |
| Issue68Budget | 기존 O0 $0.50 계약은 유지하고, 확장 Fake 계약에만 누적 $1/단계별 부담 적용 |
| Issue68HttpBoundary | 기존 Payload/HTTPS 검사 재사용, 확장은 loopback만 허용, Usage 불명·정산 충돌·전송 전 조건 위반 차단 |
| Issue68OpenAiKafkaHarness | 기존 Consumer/JPA 관측 재사용, 실시간 발행 일정, Owner 연결 및 부분 snapshot |
| SizeTimeBatchOwnerLoop | 기본 no-op 관측 hook 추가. #68에서만 Commit/실행 오류와 HOLD를 연결 |

기존 Native/Batch Listener, ACK, ErrorHandler 및 #64 상태 머신을 복사하거나 변경하지 않았다.
Owner는 계속 단일 Consumer thread에서 prepare/analyzePrepared/Retry/Commit을 실행한다.
기본 hook이 no-op인 기존 #65 안전성 검증도 재실행한다.

### Offset/종료 경계

- Owner Commit 실패는 완료 DB를 취소하지 않는다. 오류를 checkpoint/HOLD로 통지하고 새 Provider 요청을 시작하지 않는다.
- 미완료 record를 넘는 partition offset commit을 허용하지 않는다.
- 실패 시 Owner 상태, DB commit 시각, source partition/offset, committed offset, Lag, DLT를 보존한다.
- 오류 상태의 indexed/group Retry가 새 유료 전송을 일으키지 않도록 HTTP guard와 Owner 중단 관측을 연결한다.
  Production Retry/DLT 계약을 바꾼 것이 아니라 실험의 추가 호출을 차단하는 것이다.
- 각 HTTP 전 poll lease 잔여에 transport timeout60초 + 여유5초가 남는지 검사한다.
  동기 Provider 실행 중 owner가 계속 poll한다고 주장하지 않는다.
- Owner 종료 대기는75초이며 실패해도 부분 snapshot 경로를 실행한다.
  실제 느린 HTTPS/강제 종료/재균형 전체를 이번 Fake 검증이 보장하지 않는다.

## 예산 및 장부

기존 실제 장부 `.issue68-live/budget.jsonl`은 이번 실행에서 열어 append하지 않는다.
공개된 기존6개 회계 event로 만든 **temp Fake fixture**가 동일 hash/이월액을 갖는지 확인한다.
이를 실제 새 예산 장부나 실제 사용액으로 간주하지 않는다.

| 기존 이월 | 금액 |
|---|---:|
| UNKNOWN | $0.0192768 |
| 정산 | $0.0003111 |
| 부담 합계 | $0.0195879 |
| Gross 이력 | $0.0579072 |

- 실제 journal SHA256: `b2fe9f54ae8441010bebeb3734896b7328d5445586fd7bc1838045d3d510cf5e`.
- 전역 부담 = 기존/신규 정산 + UNKNOWN + inflight. Gross는 누적 선예약 이력이며 부담과 다르다.
- 전체 $1, 신규 단계 부담 $0.10/$0.50/$0.25. 이전 단계의 잔여 한도를 자동 이월하지 않는다.
- 원자적 예약 및 journal fsync 후 network exchange를 시작한다.
- 예약: input128,000 × $0.15/1M + 실제 cap × $0.60/1M.
  Native $0.0192768 / Batch10 $0.019968. Cache 할인은 보호액에서 사용하지 않는다.
- 유효한 HTTP200 Usage만 정산한다. 오류 응답에 usage처럼 보이는 값이 있어도 환급하지 않는다.
- 확장 계약은 Usage 불명 발생 시 그 요청의 예약을 유지하고, SDK Retry를 포함한 **다음 HTTP**를 차단한다.
  과거 O0의 429→200 회계 검증 계약은 소급 변경하지 않았다.
- SDK와 HTTP Usage가 불일치하면 해당 신규 Ticket의 환급만 DISPUTED_KEEP으로 복구하고 HOLD한다.
  과거 UNKNOWN/정산을 소급 정산할 수 없다.
- 잠금/일회성 claim/해시 체인/단계 순서/재시작 차단은 유지한다. 장부만 과거 상태로 돌려도 claim이 재실행을 막는다.
- 단계 중 crash한 장부는 audit 복원만 가능하다. 자동 전송 재개 경로는 없다.

공식 [gpt-4o-mini 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)에서
context128k, input0.15/cached0.075/output0.60 per1M을 재확인했다.
Deprecated 표시도 확인했으며 계정 접근 가능성을 입증하지 않는다. 승인 없이 모델을 바꾸지 않는다.
동기 Micro-batching에 OpenAI Batch API 할인을 적용하지 않는다.

### 비용 계획 — 실제 Usage 아님

| 단계 | Tokenizer/출력cap 기반 두 참고 요청형의 추정 비용 |
|---|---:|
| Pilot | $0.01452060~$0.02795310 |
| 본 비교 | $0.23001780~$0.44187480 |
| 반복 | $0.11500890~$0.22093740 |
| 신규 합계 | $0.35954730~$0.69076530 |
| 기존 부담 포함 | $0.37913520~$0.71035320 |

10건 충전 요청형과 전부 Native underfill인 요청형의 **계획 추정**이다.
실제 Usage 상한이나 maxWait별 Batch 구성 예측이 아니며, 실제 구성별 추정은 별도로 보존한다.
Retry·불명 Usage·가격 변경·다른 프로세스 사용은 이 추정에 포함되지 않는다.
SDK Retry3을 모두 비용으로 소비하는 경우 신규 추정은 최대 약$2.763이므로 $1로 완주를 보장하지 않는다.
유효 정산 후에도 다음 전체 예약액이 남지 않으면 먼저 중단한다.
계정 전체 청구·세금·다른 프로세스 지출을 보장하는 장치는 아니다.

## Gate 및 해석

- 모든80건을 Result/Category/Risk/FP/FN/Review Target 분모에 유지한다.
- Native 일반FP는 원래 오류로 보존하며 같은 단위 Batch의 신규FP와 비교한다.
- FN/검토 신호 누락/Critical 필수Category·최소Risk 실패/정책 위반/기술 오류는 HOLD한다.
- 비Critical HIGH→MEDIUM은 FLAGGED·Category·검토 신호 보존일 때만 V2 참고 분리한다.
  V1 HIGH 하향과 Risk Exact 오류는 사라지지 않는다.
- 같은 FP라도 Batch의 Category/Risk 조합이 바뀌면 보수적으로 HOLD한다.
- 오류 뒤 자동 재개 또는 더 나은 결과를 위한 추가 반복을 하지 않는다.

## 무과금 결과 및 재현

실행 명령·최종 테스트 건수·원본 경로/해시·manifest와 보존 대조는
[이번 단계 검증 JSON](expanded-preparation-verification.json)에 기록했다.
[최종 실행 raw](expanded-preparation-results.json)는 build 원본과 byte hash가 동일하다.

| 검증 | 결과 |
|---|---|
| 신규4개 테스트 클래스 | 37 tests / skipped0 / failures0 / errors0 |
| 관련 회귀 | 250 tests / skipped2 / failures0 / errors0 |
| 기존 Owner 안전성 | 14 tests / skipped0 / failures0 / errors0, 관련/전체에 포함 |
| 최종 전체 root | 1,332 tests / skipped88 / failures0 / errors0 |
| 최종 Lambda | 11 tests / skipped0 / failures0 / errors0, 실제 재실행 |
| 최종 합계 | 1,343 tests / skipped88 / failures0 / errors0 |
| test/build | SUCCESS, 6m9s, 12 tasks 실제 재실행 |
| 기존 보존 | 기존 Evidence20개/private17개 byte hash 동일 |
| 최종 raw/source | raw hash 일치, 실행 소스52개(테스트37+Production15) hash 일치 |
| 정적 확인 | JSON syntax / git diff --check PASS; 신규·미추적 파일을 포함한 변경16개 전체 whitespace 진단 없음 |

```sh
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL -u ISSUE68_O0_LIVE_APPROVAL -u ISSUE68_O0_MANIFEST_SHA256 \
  ./gradlew :test --tests '*Issue68*Test' --tests '*SizeTimeBatchSafetyIntegrationTest' \
  --tests '*ChatModerationBatch*Test' --tests '*ChatModerationServiceTest' \
  --tests '*SpringAiModerationBatchAdapterTest' --no-daemon --console=plain

env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL -u ISSUE68_O0_LIVE_APPROVAL -u ISSUE68_O0_MANIFEST_SHA256 \
  ./gradlew test build --rerun-tasks --no-daemon --console=plain
```

관련 회귀250건은 최종 raw 보존 항목 추가 전, 전체1,343건은 추가 후의 최종 소스로 실행했다.
최종 전체 결과에 신규37건과 Owner14건도 포함하며 모두 실패0이다.

### Fake Pilot120건 — 최종 전체 실행의 관측값

| 조건 | logical/HTTP | 실제 요청 항목 수 | DB 완료 | 최종 Lag/DLT | Owner 최대 Buffer |
|---|---:|---|---:|---:|---:|
| Native | 20/20 | 1×20 | 20 | 0/0 | N/A |
| Poll | 2/2 | 10,10 | 20 | 0/0 | N/A |
| Time0 | 2/2 | 10,10 | 20 | 0/0 | 10 |
| Time20 | 3/3 | 10,7,3 | 20 | 0/0 | 17 |
| Time50 | 2/2 | 10,10 | 20 | 0/0 | 10 |
| Time100 | 2/2 | 10,10 | 20 | 0/0 | 10 |

모두 SDK/HTTP Usage가 일치하고 Owner thread 위반0이었다. Time20도10건 충전으로 가정하지 않고 저장했다.
Fixture Input27,900/Output2,400/Total30,300, Cache0, 계산액$0.005625는
고정 Fake Usage에 의한 회계 시험값이며 실제 OpenAI 사용액/Token 절감 효과가 아니다.
실제 private journal의 부담$0.0195879/gross$0.0579072는 바뀌지 않았다.

별도의 Owner Time100/80건에서 Rule1건, Provider79항목, 논리/HTTP8회, DB80완료/Lag0을 확인했다.
Batch Usage는 SDK/HTTP에서 한 번만 집계하고 각 Batch DB 행의 prompt_tokens는 NULL이다.

### Owner 장애 주입

| 장애 | Fake HTTP 수신 | 보존/중단 |
|---|---:|---|
| Commit 실패 | 1 | DB 성공 유지, Lag20/미Commit, 다음 HTTP 차단 |
| 잔액 부족 | 0 | 신규 예약/전송 거절, 부분 Offset 보존 |
| Usage 없음·429·usage 포함503 오류 | 각1 | UNKNOWN 예약 유지, SDK 다음 전송도 차단 |
| Mapping·Validation·Parse·출력 잘림 | 각1 | 기술 HOLD, 다음 조건에 진입하지 않고 부분 결과 보존 |
| SAFE 오판 주입 | 1 | FN/Review Target HOLD, 이미 받은 Batch의 저장 결과 보존 |

실제 장애 빈도를 측정한 결과가 아니다. #64 Retry/Recovery-only/DLT는 기존 회귀에서 별도로 확인했다.
이번 엄격한 Live 준비 Gate를 제거하고 재시도를 계속한 결과로 표현하지 않는다.

최종 manifest SHA256:
`0a5e19a725fa9a44719d288ac8182f3c9838206f7e1f8070af169f0e27d67281`.
map key 정렬과 record 선언 property 순서에 따른 독립 Ruby 재계산도 일치했다.
이는 **무과금 준비** manifest이며 유료 승인 hash가 아니다.

첫 Gradle 실행은 cache lock의 sandbox 쓰기 제한으로 실패했으며 승인된 실행 권한으로 재실행했다.
GitHub Label 갱신도 외부 쓰기 제한으로 거절되어 우회하지 않았고 Issue 본문·Label·상태는 변경하지 않았다.
기존 테스트가 재생성한 build 내 O0 proposal은 임시 출력이며 과거 Evidence나 private 승인 manifest를 대체하지 않는다.

정상 Pilot은 실제 SDK→loopback Fake HTTP→Kafka→MySQL JPA를 통과하지만,
Fake는 GT를 echo하고 고정 Usage를 반환한다. 모델 품질/실제 Token 절감/비용 절감/운영 지연 결과가 아니다.
장애 테스트의 이전 단위 일부는 계약 fixture로만 준비하며 실제 측정 행과 구분한다.
부분 결과와 Owner/Offset snapshot은 테스트 종료 후에도 새 raw에 남긴다.

## 다음 유료 Pilot의 남은 조건

이번 확장 Session은 **paidExecutionEnabled=false**, temp/loopback 전용이다.
실제 HTTPS를 허용하는 확장 Live 진입점과 새 manifest/one-shot 승인 연결은 아직 열지 않았다.
Human의 **Pilot120건만을 대상으로 한 별도 유료 승인** 후,
동일 실행 Gate/회계 core를 고정 Live journal/HTTPS 승인에 연결하는 최소 변경과 무과금 재검증이 필요하다.
기존 O0 manifest를 새 범위의 승인으로 사용하거나 현재 Fake 시작 event를 실제 장부에 쓰지 않는다.

첫 유료 Pilot은 Native20을 먼저 평가하고, Gate 통과 시 Poll/Time0/20/50/100의 같은20건을 수행한다.
각 조건의 DB/Offset/Usage/Gate를 확인한 뒤 다음 조건을 허용한다.
정상 논리 요청30~120은 참고 범위이며 실제 HTTP 상한480 및 신규 부담0.10/전역1이 먼저 적용된다.
오류/중단은 추가 HTTP 전에 차단하고 Human 재승인 없이 재개하지 않는다.
Pilot 성공도 본 비교1,920건/반복960건의 자동 유료 승인 또는 운영 채택을 뜻하지 않는다.

## 남은 한계

- PR #67은 H2/1partition/Fake60ms였고 이번 준비는 MySQL/3partition이다. 직접 개선율로 비교하지 않는다.
- 실제 OpenAI 응답 속도에 따라 backlog/poll/Batch 구성이 달라진다. maxWait는 E2E 상한이 아니다.
- logical call 시간에는 SDK/요청 검사/장부 정산이 포함된다. HTTP attempt 시간도 Usage 판독·정산을 포함하며 순수 원격 서버 처리시간이 아니다.
  E2E에는 source hash·checkpoint·journal fsync 계측 오버헤드가 포함된다.
- Spring Listener와 owner의 구조 차이를 순수 maxWait 효과로 단정하지 않는다.
- Native 기준선을 먼저 실행하므로 시간·캐시 효과를 완전히 제거하지 못한다. 실제 Cached Usage와 미할인 비용을 함께 보고한다.
- Burst 외 패턴은 본 계획에서1회이므로 반복 안정성 입증이 아니다.
- 실제 모델 접근·품질·HTTP Retry·TLS·청구·느린 Provider에서 종료/lease는 이번 실행에서 미검증이다.
- 이번 장부 재시작은 temp 파일 close/reopen, 잠금 검증은 동일 JVM의 중복 open이다. 실제 독립 JVM kill/재시작 보장으로 확대하지 않는다.
- Source/GT/metadata/예산 변경은 새 동결·승인이 필요하며 과거 V1 HOLD를 PASS로 바꾸지 않는다.
