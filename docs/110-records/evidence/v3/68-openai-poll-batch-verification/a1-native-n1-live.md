# Issue #68 — 승인된 A1 Native N1: 첫 Critical 사례 HOLD

## 범위 및 결론

2026-10-22 Human이 동결 Manifest 기준 A1 Native 최대8건만 유료 승인했다.
N1을 한 번 시작했으나 첫 사례 `HOLDOUT-PROFANITY-14`가
`FLAGGED / PROFANITY / MEDIUM`으로 반환됐다. 기존 GT의 HIGH 및 Critical 최소 위험도를
충족하지 못하여 **Gate V1/V2 HOLD**, 추가 발행·HTTP 없이 종료했다.

**완료1건 / 미실행7건**이다. 8건 완료, A1 품질 통과 또는 Batch 채택이 아니다.
FP/FN이 없더라도 이번 Critical Risk 하향은 차단 대상이다. N2/Batch/Control 재측정/반복/
기존 Pilot/전체 Matrix는 실행하지 않았다. 과거 HOLD를 해제하거나 소급 PASS로 변경하지 않았다.

## 승인·환경·불변 확인

- branch `feature/68-openai-poll-batch-verification`, 기준 HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807` + 기존 미커밋 Harness.
- 승인 ID `issue68-a1-native-n1-eight-v1`.
- [동결 Manifest](a1-native-n1-approval-proposal.json) SHA-256 `3d959747d3334bdf3d8fbc3ea911cdc8f6e5a09305bd30ce94a0cef915bd26cc`.
- 후보 `issue68-risk-boundary-a1-test-only`, Prompt SHA-256 `c4a3f361f7325ac811f9e2f0ce35b3cd1934b536910ac89fbd903ca35e1c7960`.
- Model 요청 `gpt-4o-mini`, 실제 응답 `gpt-4o-mini-2024-07-18`. Policy v2, 기존 Native Schema/cap128/Sampling 미설정 유지.
- GT SHA-256 `2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b`, metadata SHA-256 `a3a424323e5585da1b9a611281ac37174c60c4b946987e541c44ad5981cd40b5` 유지.
- 실행 전/후 Manifest에 연결된 테스트 소스54개/Production 소스15개 해시 일치.
  이번 실행에서 Java, Production 설정, GT/Gate, Dashboard를 수정하지 않았다.
- 실제 Native Consumer/Adapter/ChatClient/SDK/JPA, Testcontainers Kafka7.7.1/MySQL8.4.
  partition3 중1개 사용, concurrency1, max.poll.records10, RECORD/autoCommitFalse.
  이전 사례의 DB/Offset 완료 뒤 다음 사례를 발행하는 승인 순서를 유지했다.
- 과거 사용한 합성 평가 데이터다. 실제 사용자 데이터 및 신규 블라인드 평가가 아니다.
- `.env`는 source하지 않았다. `OPENAI_API_KEY` 항목 하나만 안전하게 읽어 승인된 Gradle
  자식 환경에 일시 전달했고 값은 명령 인수·콘솔·Evidence에 기록하지 않았다.
- DB `prompt_version` 및 기존 업무 Metric의 태그는 Production 버전 그대로다.
  실제 wire System은 테스트 전용 A1이며 위 Manifest/요청 해시로 구분한다.
  이 관측을 Production Prompt 실행으로 합산하지 않는다.

## 실행 시각과 단일 실행

- Gradle 시작/종료 UTC: `2026-10-22T05:54:55Z` ~ `2026-10-22T05:55:22Z` (컴파일·컨테이너 기동 포함).
- Harness 시작/종료 UTC: `2026-10-22T05:55:15.920946Z` ~ `2026-10-22T05:55:21.520439Z`.
- Harness KST: `2026-10-22 14:55:15.920946` ~ `14:55:21.520439`.
- 실행 필터: `./gradlew :test --tests '*Issue68A1N1LiveTest' --rerun-tasks --no-daemon --console=plain`.
  N1 승인 ID와 정확한 Manifest 해시만 활성화하고 다른 Issue68 승인 환경변수는 제거했다.
- 기존 claim/장부를 초기화하지 않았다. 새 N1 claim과 별도 체크포인트를 생성하고,
  같은 고정 journal에 `A1_N1_BEGIN`, attempt5 예약·정산만 append했다.
  HOLD이므로 `A1_N1_END`는 없으며 claim을 유지한다. 자동 재개·재실행하지 않는다.

## GT 및 실제 결과

모든 Case ID에는 `HOLDOUT-` 접두어가 있다. 미실행을 SAFE/정답/UNRESOLVED로 대체하지 않는다.

| Case | 동결 GT: Result / Category / Risk | 실제 결과 | 판정 |
|---|---|---|---|
| PROFANITY-14 | FLAGGED / PROFANITY / HIGH | FLAGGED / PROFANITY / MEDIUM | Critical 하향, HOLD |
| PROFANITY-15 | FLAGGED / PROFANITY / HIGH | NOT_RUN | 앞선 HOLD로 미발행 |
| PROFANITY-16 | FLAGGED / PROFANITY / HIGH | NOT_RUN | 미발행 |
| PROFANITY-01 | FLAGGED / PROFANITY / MEDIUM | NOT_RUN | 미발행 |
| PROFANITY-17 | SAFE / [] / LOW | NOT_RUN | 미발행 |
| PROFANITY-13 | SAFE / [] / LOW | NOT_RUN | 미발행 |
| SAFE-17 | SAFE / [] / LOW | NOT_RUN | 미발행 |
| SAFE-01 | SAFE / [] / LOW | NOT_RUN | 미발행 |

실행한1건만의 집계:

- TP1 / TN0 / FP0 / FN0. Result Exact1/1, Category Exact1/1, Risk Exact0/1.
- HIGH 하향1 / Critical 보호 실패1 / Review Target 누락0 / 관측된 cross-field 위반0.
- 기술적 UNRESOLVED0. 남은7건은 실행하지 않아 FP/FN 및 정확도 분모에 포함하지 않는다.
- V1 `GT_HIGH_DOWNGRADE`, V2 `CRITICAL_RISK_DOWNGRADE`를 독립 보존했다.
- FLAGGED/Category/MEDIUM 이상 검토 신호는 유지됐지만 Critical 하향의 허용 사유가 아니다.
  실제 운영자의 자동 검토가 수행됐다는 의미도 아니다.
- 과거 Control도 이 Case에서 MEDIUM이었고 이번 A1도 MEDIUM이다.
  이번1회에서는 목표 오류 개선이 관측되지 않았다. 동시점 Control·반복·Batch 비교는 하지 않았으므로
  원인, 전체 A1 품질, 다른 Case 또는 Native/Batch 차이를 단정하지 않는다.

## 실제 Usage / 비용 / 지연

| 항목 | 실제 관측 |
|---|---:|
| Native 논리 요청 / 실제 HTTP | 1 / 1 |
| Batch / 추가 HTTP Retry | 0 / 0 |
| HTTP status / encoding / 정산 | 200 / gzip / SETTLED |
| Input / Cached Input / Output / Total Token | 978 / 0 / 20 / 998 |
| SDK Usage = HTTP Usage | 일치 |
| 신규 Estimated Cost (cache 적용/미적용 동일) | $0.00015870 |
| HTTP exchange latency | 2,818.500375ms |
| 논리 호출 latency | 3,128.064459ms |
| Spring AI operation Timer | 3,093.143709ms |
| Kafka 최초 수신 → JPA afterCommit | 3,187.424375ms |
| 발행 ACK → JPA afterCommit | 3,199.927583ms |

지연은 서로 다른 관측 경계다. N=1이므로 대표 p50/p95나 운영 SLO로 일반화하지 않는다.
로컬 Tokenizer inputEstimate1,182는 실제 Provider Input978과 다르며 비용에 대입하지 않았다.
비용은 [공개 모델 단가](https://developers.openai.com/api/docs/models/gpt-4o-mini)의
input $0.15 / cached $0.075 / output $0.60 per1M과 실제 Usage 기준 **Estimated Cost**이지
청구서 금액이 아니다. 이번 cached0으로 할인 적용/미적용 금액이 같다.

## 장부 및 차단 상태

| 항목 | 실행 전 | 실행 후 |
|---|---:|---:|
| 실제 Usage 기준 누적 정산 추정액 | $0.00044625 | $0.00060495 |
| 기존 UNKNOWN 예약 | $0.01927680 | $0.01927680 |
| 진행 중 예약 | $0 | $0 |
| 정산 + UNKNOWN + 진행 중 부담 | $0.01972305 | $0.01988175 |
| gross 예약 이력 | $0.07718400 | $0.09646080 |

- 신규 N1 attempt5는 전송 전 $0.01927680 예약 후 $0.00015870으로 한 번 정산했다.
  신규 UNKNOWN0/미정산0. 신규 최종 부담 $0.00015870 < 진단 누적 한도 $0.10.
- 이번 예약 시점의 전역 최대 부담 $0.03899985, 최종 $0.01988175 < 전역 $1.00.
  gross는 과거 예약 이력 합계이지 실제 지출이나 현재 부담이 아니다.
- 기존9개 journal record의 byte prefix 해시는 `80607e4746092d22067d61b818e69bd61f493e197f1a6d4d4cc58789c4fa6547`로 불변.
- append 후 전체 journal SHA-256 `35924fde9b6de4437b5294b9e23daf48322c51daa012c509f08aa15c893e01d0`.
  12개 record의 hash-chain, ticket별 단일 정산, 기존 UNKNOWN 유지 확인 PASS.
- Quality HOLD가 다음 발행/전송을 막았다. 예산·32회 전체/4회 논리당 한도를 소진한 실행이 아니며
  Live 장애 Retry나 Crash 복구를 이번 정상 HTTP1회로 입증하지 않는다.

## Kafka / DB / 관측 보존

- 발행 및 onConsume1건, 원본 partition0/offset0, delivery1.
- 실제 JPA afterCommit1건, DB 상태 FLAGGED, 원래 모델 MEDIUM 그대로 저장. GT에 맞춘 보정 없음.
- Kafka committed next offset1, snapshot Lag0, unsafe commit0, DLT0.
- 나머지7건은 발행하지 않았으므로 Lag0이 전체8건 처리 완료를 의미하지 않는다.
- Provider/Parse/Validation/DB/Offset/Usage 오류는 이번 실행에서 관측되지 않았다.
- 정상 응답 저장 후 Quality HOLD로 테스트가 실패 종료됐다. 이미 안전하게 완료한 현재 record의
  Offset을 되돌리거나 나머지 Case를 실행하지 않았다.
- Context 종료 전 HTTP/SDK/GT 비교, 최초 수신·ACK·afterCommit·반환, Offset/Lag 및 Meter snapshot을
  새 N1 checkpoint8개에 보존했다. Spring AI 논리1, OkHttp1, Token978/20/998, route llm1,
  final FLAGGED1을 snapshot에서 확인했다. Grafana 수정/추가 캡처는 하지 않았다.
- 격리 DB는 테스트 종료로 제거된다. 보존 대상은 종료 전 관측 snapshot이며 운영 DB의 영속 복구를
  검증한 결과는 아니다.

## 검증과 파일 보존

- 현재 실행 `compileJava` / `compileTestJava`: PASS.
- Live test: **1 test /1 failure /0 errors /0 skipped**, Gradle exit1, `BUILD FAILED in 26s`.
  원인은 `ISSUE68_O0_EXECUTION_GATE_HOLD`로 연결된 승인 품질 중단이다. 이를 테스트 PASS나
  전체 Build 성공으로 기록하지 않는다. Gate가 중단시킨 뒤 재실행하지 않았다.
- 무과금 회귀 및 전체 Build는 [직전 동일 소스 Readiness](a1-native-n1-readiness.md)의 결과로만 참조한다.
  당시 root1,440 + Lambda11 =1,451 tests /92 skipped /0 failures/errors였다.
  이번에는 소스를 바꾸지 않았고 추가 전체 회귀를 실행하지 않았다.
- 실행 전 기존 파일 전체 해시 집합과 실행 후를 대조: 승인된 journal append 외 기존 파일 변경0.
  기존 Evidence/raw/checkpoint/GT/Production/Dashboard 보존. 새로운 N1 claim 및8개 checkpoint만 추가.
- 이번 신규 공개 Evidence: 이 문서와 [실제 관측 JSON](a1-native-n1-live-results.json).
  JSON에는 새로운 checkpoint 해시와 원래 partial/final snapshot을 연결하고 비밀정보는 넣지 않는다.
- `git diff --check`, 신규 Evidence JSON syntax/관측값 대조/whitespace/민감정보 패턴 검사 PASS.
  Commit/Push/PR/Issue 변경 없음.

## 다음 단계

A1은 이번 Critical14 하향을 해결하지 못했다. N1 나머지7건은 미검증이며 전체 채택 판정을 내리지 않는다.
기존 V1/V2 HOLD와 Production Batch 기본 OFF를 유지한다. 추가 진단·후보 변경·재개는
별도 Human 결정과 승인 계약이 필요하며, 이번 claim/예약/결과를 초기화하지 않는다.
