# Issue #68 — 품질 Gate HOLD 보존 및 비운영 OpenAI 사용량 비교

## Human 승인된 종료 판정

2026-10-22 Human이 승인한 **품질 Gate 미달에 따른 조기 종료** 판정은 그대로 보존한다.
이후 별도 승인으로 품질 판정과 분리된 비운영 Usage Benchmark480관측을 완료했다.
이는 과거 HOLD 해제나 원래 품질 Matrix의 PASS가 아니다. 아래는 최신 결과 색인이며,
뒤의 Phase 1 원문 및 연결된 과거 Evidence/raw를 새 결과로 덮어쓰지 않는다.
제출 Commit/PR·CI·AI Review의 최신 상태는 연결 Draft PR을 기준으로 확인한다.
Issue Close와 운영 활성화는 수행하지 않으며 상위 #55는 OPEN을 유지한다.

| 검증 축 | 최종 판정 | 범위 및 한계 |
|---|---|---|
| 기술적 통합, Kafka/DB/Offset | 검증 완료 | Fake 장애 경계와 실제 OpenAI 소규모 정상 응답의 개별 Commit·Offset 관측. 운영 장애 전체 보장 아님 |
| Fake 충전율·호출 수·E2E 비교 | 완료 | #62/#65 로컬 Kafka/H2/Fake60ms 결과. 실제 Provider 성능으로 대체하지 않음 |
| HTTPS·Usage·예산·HOLD 안전장치 | 검증 완료 | 무과금 차단/재시작 계약 및 Live 예약·정산·품질 중단 확인. 계정 전체 청구 상한 보장 아님 |
| 기존 Grafana Dashboard 연동 | Fake 검증 완료 | 기존 패널/UID 불변, 격리 Prometheus 일시 연결 후 원복. Live 자동 수집이나 테스트 MySQL 패널 연결은 아님 |
| 실제 OpenAI 품질 | **Critical Gate FAIL/HOLD** | Control/A1 Native 오류 및 Benchmark의 FN/FP/하향을 보존. 검토 신호 유지와 누락은 운영 영향이 다름 |
| 원래 품질 평가 Matrix | **NOT_RUN / 미완료** | Pilot Native1건 뒤 중단. Native80/Poll80 O0/O1·본 비교1,920건·반복960건 미실행 |
| 별도 실제 OpenAI 사용량 비교 | **480관측 완료 / 품질 PASS 아님** | 기존 진단20×6조건×4유입. Burst120+나머지360, 실제 Usage·지연·정합성 측정. 신규 블라인드 표본 아님 |
| Production Batch | **기본 OFF / 운영 적용 보류** | Kafka Poll-Batch를 우선 도입 검토 후보로 유지. 추가 FN/Critical 검증 후 Human이 활성화 여부 결정 |
| A1 | **Production 미채택** | 목표 오류 개선 미관측, N1 나머지7건 미실행. 전체 후보 품질 판정으로 확대하지 않음 |

최종 결론은 “상당한 실제 Token·추정 비용 절감과 기술 연계는 관측했지만 품질 기준은 미달이며,
사후 관리자 검토 구조에서 오류별 영향을 구분해 Poll-Batch 도입 검토를 계속한다”이다.
실험상 품질 기준 미달과 서비스 운영상 수용 가능성은 서로 다른 판단이다.
후자의 수용 범위도 아직 승인되지 않았다. 전체 실험 PASS/품질 유지/운영 활성화 완료로 표현하지 않는다.
남은 승인 수량을 채우려고 재실행하지 않으며 기존 Pilot/O0/O1/N2/Batch 진단은 자동 재개하지 않는다.

## 실제 사후 검수 구조와 운영 영향

다음은 현재 백엔드에서 확인한 동작이다. 운영 의도와 미구현 제재 집행을 구분한다.

1. [ChatMessageCommandService.send()](../../../../../src/main/java/com/bobfull/chat/application/service/ChatMessageCommandService.java)는
   메시지/Outbox를 저장하고 Commit 후 Kafka 후속 처리 및 Redis 실시간 전파를 요청한다.
   AI 판정 결과를 받아 메시지를 사전 차단하거나 자동 제재하는 분기는 없다.
2. [ChatModerationService](../../../../../src/main/java/com/bobfull/chat/application/service/ChatModerationService.java)와
   Batch 경로는 Rule/LLM 분석·Validation 후 메시지별 검수 결과를 저장한다.
   이 경로가 회원 제재를 실행하지 않는다.
3. [MemberModerationQueryRepositoryImpl](../../../../../src/main/java/com/bobfull/admin/infrastructure/repository/query/MemberModerationQueryRepositoryImpl.java)은
   `status=FLAGGED`를 관리자 회원 집계/근거 조회에 포함한다. 그중 MEDIUM/HIGH 메시지를 distinct로 세고,
   [MemberModerationQueryService](../../../../../src/main/java/com/bobfull/admin/application/service/MemberModerationQueryService.java)는
   회원별 검토 대상3건 이상을 `REVIEW_REQUIRED` 신호로 반환한다.
   HIGH와 MEDIUM은 같은1건이며, LOW는 FLAGGED 근거에는 남지만 이 건수에서는 제외된다.
   현재 목록 정렬은 최근 분석시각 기준이고 Risk 기반 자동 제재/검토 우선순위는 확인되지 않았다.
   `REVIEW_REQUIRED`는 응답 신호이지 실제 관리자의 검토 수행 보장이 아니다.
4. [ChatRoomMemberReportService.create()](../../../../../src/main/java/com/bobfull/chat/application/service/ChatRoomMemberReportService.java)는
   참여 이력·근거 메시지·중복 등을 검사해 사용자 신고를 생성한다. AI SAFE/FLAGGED/결과 존재 여부를
   신고 전제조건으로 사용하지 않는다.
5. [AdminModerationReportService.get()/review()](../../../../../src/main/java/com/bobfull/admin/application/service/AdminModerationReportService.java)는
   신고·주변 메시지·AI 결과·회원 누적 신호를 제공하고, Human의 `NO_VIOLATION`/`VIOLATION_CONFIRMED`,
   검토자·시각을 기록한다. 신고 상세에서는 SAFE인 주변 메시지의 AI 결과도 볼 수 있다.
   **이 review 경로는 회원 제재 상태나 AI 분류를 변경하지 않는다.**
   실제 계정 정지/추방 등 제재 집행까지 연결된 구현은 확인되지 않았으므로 완료 기능으로 주장하지 않는다.

| 오류 | 실험 판정 — 불변 | 확인된 서비스 영향 |
|---|---|---|
| HIGH→MEDIUM, FLAGGED/Category 유지 | Risk Exact/Critical 최소HIGH 기준 미달, 기존 HOLD 유지 | 관리자 근거·MEDIUM/HIGH 건수·3건 경계는 유지. 위험도 정보는 부정확하며 실제 검토 누락과 동일시하지 않음 |
| FLAGGED→SAFE(FN) | Result/Category/Risk·Review Target 오류. Critical이면 보호 실패도 기록 | AI 기반 FLAGGED 목록/검토 건수에서 빠져 검토 신호가 누락될 수 있음. 예: 다른2건+누락1건은3건이 아닌2건으로 집계 |
| SAFE→FLAGGED(FP) | 기존 FP 및 Native 대비 새 FP를 보존 | 불필요한 관리자 근거/검토 부담. MEDIUM/HIGH면 검토 건수·3건 경계도 늘어남. 자동 제재 피해와는 구분 |
| Category 불일치 | Category Exact·필수 Category 회귀를 별도 집계 | 잘못된 검토 사유·종류별 집계 정보 제공 |
| Risk 불일치/LOW 하향 | Risk Exact·HIGH 하향·Review Target를 별도 집계 | HIGH→MEDIUM과 달리 LOW 하향은 FLAGGED여도 검토 건수에서 제외될 수 있음 |

신고는 보완 경로이지 모든 FN을 발견하는 장치가 아니다. AI SAFE는 AI 목록에서 빠지지만 신고 상세에
다시 나타날 수 있으므로 “어떤 관리자 화면에서도 볼 수 없음”이라고 단정하지 않는다.
관리자 판단 기록과 실제 제재 집행 결과, 실제 검토 처리량·신고율·검토 누락의 운영 빈도는 아직 별도 검증이 필요하다.

## 최신 Usage Benchmark와 도입 검토 근거

[480관측 상세](usage-benchmark-480-live.md) 및 [실측 집계](usage-benchmark-480-live-results.json)를 공식 근거로 사용한다.
동일 진단20건의4유입 패턴 반복 관측이며 운영 품질을 대표하지 않는다.

- Poll Total16,269 vs Native67,172: **75.8% 절감**.
  Cache 할인 반영 예상 비용 $0.00310770 vs $0.01071300: **71.0% 절감**.
- Native FN0 vs Poll FN1, Poll의 Native 대비 새 FP3, 양쪽 Critical 실패9관측을 보존한다.
  Native에도 HIGH→MEDIUM이 있으나 이를 이유로 Poll의 새 FN을 면제하지 않는다.
- Size/Time50은 Total14,624로 더 적었지만 FN3관측이 발생했다. 충전율·지연·복잡성과 품질을 함께 봐야 한다.
- 구조가 단순하고 실제 Token/예상 비용 이득이 있는 **Kafka Poll-Batch를 우선 도입 검토 후보로 유지**한다.
  이는 운영 채택이 아니다. 추가 FN 회귀·Critical 보호 검증 및 Human의 수용 기준 확정 후 활성화 여부를 결정한다.
- 기존 Gate V1/V2 HOLD·Production Batch OFF·GT 및 실험 점수는 그대로다.

## 선행 PR 성과와 #68의 구분

| 기록 | 실제 범위·성과 | 해석 제한 |
|---|---|---|
| [PR #60 / #56~#58](https://github.com/gpekd5/bobfull-backend/pull/60) | 독립 OpenAI 동일79 LLM 대상 비교, Prompt A/B/C/D·반복·형식·크기·순서·구성·Checklist/Q1 실험 | Kafka/DB E2E 아님. 비용 절감과 품질 회귀를 함께 기록 |
| [PR #61 / #59](https://github.com/gpekd5/bobfull-backend/pull/61) | Poll-Batch 구현, Rule/완료 Skip, 중립ID, 개별 저장, Retry/DLT 및 Fake 조합 기동 | 기능 병합은 운영 활성화가 아님 |
| [PR #63 / #62](https://github.com/gpekd5/bobfull-backend/pull/63) | Kafka/H2/Fake60ms 24실행/792건. Native321 vs Poll113 논리 요청 | 실제 Token·비용·모델 품질은 미측정 |
| [PR #66 / #64](https://github.com/gpekd5/bobfull-backend/pull/66) | 같은 지속 공유 장애5건/max2에서 논리10→2회, 그룹 Retry/recovery-only·DLT/Offset | 현재 할당 메모리 범위. JVM 재시작을 넘는 영속 상한·외부 exactly-once 아님 |
| [PR #67 / #65](https://github.com/gpekd5/bobfull-backend/pull/67) | Kafka/H2/Fake60ms 72실행/2,376건. Native/Poll/Time0·20·50·100ms=321/111/110/89/80/66 논리 요청 | Size/Time은 지연·복잡성 증가도 관측. Production 미도입 |
| #68 현재 작업 | 실제 Consumer/Adapter/SDK/MySQL·안전장치·HOLD 보존·Fake Dashboard 및 별도 유료480관측 Usage 비교 | 원래 품질 Matrix 미완료/HOLD, 운영 품질 유지 미입증. 제출 검증은 아래 최신 기록, CI/Review는 연결 PR 기준 |

위5개 PR은 GitHub Merge 상태를 확인했다. 실행 당시 HEAD/source hash는 각 원본 그대로 유지한다.
#68 기준 HEAD는 `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`, 브랜치는
`feature/68-openai-poll-batch-verification`이며 실험 당시 Harness는 미커밋 상태였다.
자기 자신의 Commit SHA나 미래 Head에서 실행한 것으로 원본을 재작성하지 않는다.

### 실제 Token·비용과 Fake 호출·지연의 별도 근거

- [독립 OpenAI 비교](../55-ai-moderation-efficiency/microbatch-comparison.md):
  Native Total66,306 → Batch10 Total11,177(83.14% 감소).
  Estimated Cost는 Native $0.01053630 → Batch10 $0.00256080(cache 미반영)/$0.00233040(cache 반영,77.88% 감소).
  Native72/80, Batch10 74/80이어도 새 FP(SAFE-17)1건 및 새 HIGH 하향(PROFANITY-15/SPAM-08)2건이 있었다.
  F0 Batch5의 PROFANITY-16 FN3/3 등 후속 품질 오류도 보존한다. 운영 절감률이 아니다.
- [#62 Fake E2E](../62-kafka-poll-batch-measurement/README.md):
  저유입은 Batch 요청0, 일정 간격 평균 크기2, Burst5.588, 짧은 연속6.571.
  Burst 수신→Commit p95는 Native548.752ms/Poll80.283ms. 고정 Fake 대기 결과다.
- [#65 Fake 6조건 비교](../65-size-time-batch-comparison/README.md):
  저유입 Poll p95 74.225ms/Time100 177.210ms로 호출 이득 없이 대기 증가.
  일정 간격은 Poll46요청/73.460ms, Time100 18요청/174.134ms.
  호출 감소만으로 Size/Time 채택을 결정하지 않았다.

## #68 실제 유료 실행과 기술·품질 결과

| 단계 | 생성 논리/HTTP | 실제 Input / Cached / Output / Total | Usage 기반 Estimated Cost | 판정 |
|---|---:|---|---:|---|
| 최초 Native Smoke | 1/1 | SDK·DB824 / cached 미확정 /13 /837 | HTTP 장부 미확정; $0.01927680 예약 유지 | HTTP200이나 USAGE_UNKNOWN HOLD |
| Usage 수정 후 Native | 1/1 | 824 /0 /13 /837 | $0.00013140 | SAFE-01 GT 일치, 기술 경계 정상 |
| Usage 수정 후 Poll-Batch | 1/1(2항목) | 986 /0 /53 /1,039 | $0.00017970 | ID/개별 Commit·Offset 정상; PROFANITY-16 HIGH→MEDIUM으로 V1 HOLD |
| 6조건 Control Pilot Native | 1/1 | 821 /0 /20 /841 | $0.00013515 | PROFANITY-14 Critical HIGH→MEDIUM, V1/V2 HOLD |
| A1 Native N1 | 1/1 | 978 /0 /20 /998 | $0.00015870 | 동일 Critical 오류, V1/V2 HOLD; 나머지7건 NOT_RUN |

최초 Usage 판독 오류를 후속 성공으로 소급 정산하지 않는다. 최초 응답 Content-Encoding은 미확인이다.
후속 gzip 판독 성공은 과거 UNKNOWN 원인이 gzip이었다는 확정 증거가 아니다.
위 생성 HTTP 합계와 과거 모델 접근 확인 GET(하위 Attempt 미계측)은 구분한다.

Native와 Batch Smoke는 입력 개수/구성이 다르며, Control과 A1도 실행 시점이 다르다.
따라서 단순 Token·시간 비율을 이번 동일 조건 Before/After 개선율로 계산하지 않는다.
A1의 요청당 증가157 Input Token은 해당 Case의 관측 차이이며 전체 Dataset 평균이 아니다.
원본의 Tokenizer 추정치와 실제 Usage, cache 할인·미할인 비용을 구분한다. 비용은 청구서가 아니다.

### 품질 실패 원인과 A1 미채택 근거

- PROFANITY-14는 동결 GT FLAGGED/PROFANITY/HIGH 및 Critical 최소HIGH를 유지한다.
  Control과 A1 모두 FLAGGED/PROFANITY/MEDIUM. FP/FN0·검토 신호 유지도 Critical 하향을 면제하지 않는다.
- 과거 대표 Native6회도 MEDIUM이었다. Native 공통 오류와 Batch 고유 회귀를 구분한다.
  기존 Batch FP/FN/하향 관측도 공통 오류를 이유로 무시하지 않는다.
- Validator는 필수값/SAFE 조합/FLAGGED Category를 검사하며 위 MEDIUM은 구조적으로 유효하다.
  Service는 Risk를 그대로 저장한다. Mapping·DB가 HIGH를 MEDIUM으로 바꿨다는 확인된 증거는 없다.
- 조건부 위협과 적대감 경계 적용, Few-shot·형식·구성·모델 변동은 원인 후보이지 확정 원인이 아니다.
  A1 한 번의 실패로 모든 Prompt 개선 가능성을 부정하거나 다른 모델 성능을 추정하지 않는다.
- A1 추가 설명은 목표 오류를 개선하지 못했고 나머지7건은 미검증이다. A1을 Production에 반영하지 않는다.
- Review Target 유지가 실제 관리자의 자동 검토 수행을 보장하지 않으며 사용자 신고가 FN을 모두 보완하지 않는다.

### 실제 지연·정합성의 검증 범위

- 수정 후 Smoke: Native 수신→Commit2.827807167s, Batch 두 항목1.830086208/1.843901500s.
  DB1/2건, next offset1/2, 최종 Lag0, DLT0·unsafe Commit0. 서로 다른 입력의 소표본이다.
- Control Pilot: 완료1/20, 나머지19건 미완료, Lag19 보존. 초기 HOLD snapshot의 수신 시각 누락을
  이후 Harness에서 보완했으나 과거 지연을 새 코드 값으로 채우지 않았다.
- A1 N1: 수신→Commit3.187424375s, ACK→Commit3.199927583s, DB1/next offset1/Lag0/DLT0/unsafe Commit0.
  나머지7건은 미발행이므로 Lag0은8건 완료가 아니다.
- 위 Smoke/N1만으로 조건별 p50/p95·4패턴·반복 안정성을 입증하지 않는다.
  이후 별도 Usage Benchmark의4패턴 p50/p95 관측은 연결 문서로 구분하며, 반복 안정성·운영 장애 분포는 여전히 미검증이다.
  Fake lifecycle/장애 테스트를 실제 OpenAI 재균형·강제 종료 검증으로 대체하지 않는다.

## 원래 품질 Matrix 미완료와 조기 종료 계약

| 계획 | 실제 실행 | 최종 상태 |
|---|---|---|
| 기존 Native80/Poll80 O0 및 O1 | 유료 전체 비교 없음 | NOT_RUN |
| 확장 Pilot120(6조건×20) | Control Native 첫1건 후 중단; 나머지119건 미평가 | 부분 실행/HOLD |
| 본 비교1,920건 | 없음 | NOT_RUN |
| 조건부 Burst 반복960건 | 없음 | NOT_RUN |
| A1 N1 최대8건 | 첫1건 후 중단; 나머지7건 미발행 | 부분 실행/HOLD |
| A1 N2/Batch/Control 재측정 | 없음 | NOT_RUN |
| 별도 비운영 Usage Benchmark480 | Burst120+LOW/INTERVAL/SHORT360 | 완료. 의미상 오류 기록 지속 계약이며 품질 PASS 아님 |

원래 전체 비교 완료 조건을 충족했다는 뜻이 아니라 **Human 승인된 품질 실패 조기 종료**다.
추가 유료 실행을 종료의 전제조건으로 삼지 않는다. #55의 최종 품질·운영 활성화 결정은 남겨 OPEN 유지한다.
#68 Close 및 보존 PR은 다음 Human 검토/승인 이후 별도 작업이다.

## 예산·Private 원본 보존

최신 Usage Benchmark 종료 장부는 [480관측 기록](usage-benchmark-480-live.md)을 따른다.
알려진 보수적 정산 $0.02773380 + 기존 UNKNOWN $0.01927680 + 진행 중0 = 부담 **$0.04701060**,
gross 예약 이력 $2.97492480은 실제 지출이 아니다. 전역 상한$1.00 안이며 기존 장부를 초기화하지 않았다.
현재312이벤트 SHA는 `63a30ae34cbf3b07773c9f257a2107bcbbf7b33d08a85c35550bf300242952a1`이다.

아래는 **이전 N1 종료 당시의 역사적 장부 값**이며 최신 총액으로 해석하지 않는다.

| 항목 | 값 |
|---|---:|
| Usage 정산 누적 추정액 | $0.00060495 |
| 기존 UNKNOWN 유지 | $0.01927680 |
| 진행 중 예약 | $0 |
| 전체 부담(정산+UNKNOWN+진행 중) | $0.01988175 |
| gross 예약 이력 | $0.09646080 |
| 최종 전역 보호 상한 | $1.00 |

장부 SHA-256 `35924fde9b6de4437b5294b9e23daf48322c51daa012c509f08aa15c893e01d0`.
과거 prefix·claim·checkpoint·UNKNOWN을 삭제/초기화/정산하지 않는다.
`.issue68-live/` 전체는 로컬 Private 상태로 유지하고 Git ignore한다. 공개하는 것은 비식별 결과·금액·해시이며
Private journal/실행 checkpoint 파일 자체를 Git에 포함하지 않는다. 승인 Manifest 보존은 재실행 권한이 아니다.
`.env`/Key/개인정보/실사용자 원문/`build/`도 제출에서 제외한다.

## Evidence 색인 — 단계별 원본

| 단계 | 문서 및 원본 |
|---|---|
| Phase1 Fake/GT/기술 연계 | 아래 보존 본문, [verification-results.json](verification-results.json) |
| Live 준비 / 최초 UNKNOWN | [live-readiness.md](live-readiness.md), [live-smoke-results.json](live-smoke-results.json), [live-smoke-stop-evidence.json](live-smoke-stop-evidence.json) |
| Usage 판독·정산 수정 | [usage-accounting.md](usage-accounting.md), [usage-accounting-results.json](usage-accounting-results.json) |
| 명시 재개 Smoke / V1 HOLD | [live-resume-usage-v1.md](live-resume-usage-v1.md), [결과](live-resume-usage-v1-results.json), [audit](live-resume-usage-v1-audit.json) |
| GT 재검토 / V2 동결 / O0 무과금 | [human-gt-gate-v2-review.md](human-gt-gate-v2-review.md), [metadata](gate-v2-metadata-v1.json), [o0-readiness.md](o0-readiness.md), [o0-approval-safety.md](o0-approval-safety.md) |
| 6조건 Fake 확장·예산·HTTPS | [expanded-preparation.md](expanded-preparation.md), [expanded-live-readiness.md](expanded-live-readiness.md), 관련 results/verification JSON |
| Control Pilot Critical HOLD | [expanded-pilot-live.md](expanded-pilot-live.md), [결과](expanded-pilot-live-results.json) |
| 원인 후보·HOLD 관측 보완 | [critical-hold-observability.md](critical-hold-observability.md), [Fake raw](critical-hold-fake-monitoring.json) |
| 기존 Dashboard 실제 Fake 패널 표시 | [dashboard-panel-verification.md](dashboard-panel-verification.md), [Fake raw](dashboard-panel-fake-results.json), [runtime export](dashboard-runtime-export.json) |
| A1 무과금 후보 / N1 연결 | [risk-boundary-a1.md](risk-boundary-a1.md), [freeze](risk-boundary-a1-freeze.json), [a1-native-n1-readiness.md](a1-native-n1-readiness.md), [승인 Manifest](a1-native-n1-approval-proposal.json) |
| A1 Native N1 Critical HOLD | [a1-native-n1-live.md](a1-native-n1-live.md), [결과](a1-native-n1-live-results.json) |
| 비운영 Usage Benchmark480 | [상세·서비스 영향 해석](usage-benchmark-480-live.md), [집계·Case 비교](usage-benchmark-480-live-results.json), [Burst](usage-benchmark-burst-live-results.json), [LOW](usage-benchmark-low-live-results.json), [INTERVAL](usage-benchmark-interval-live-results.json), [SHORT](usage-benchmark-short-live-results.json) |

Dashboard의11개 panel/UID/PromQL/디자인은 불변이다. Fake 수집은 별도 TSDB/URL 일시 전환 후 원복으로
기존 앱 데이터와 격리했다. 기존 MySQL 패널은 Testcontainers DB를 자동 조회하지 않는다.
캡처 파일은 Human 요청으로 생략했으며 저장되지 않은 이미지를 Evidence에 있다고 표현하지 않는다.

## PR #69 CI 연결 경계 보완 — 2026-10-23

최초 제출 Head `1f27b618ebb5d12bfd1da528b1298023fe8077cb`의
[GitHub CI](https://github.com/gpekd5/bobfull-backend/actions/runs/39799150858)는 **FAILURE**였다.
root1,486건 중 Dashboard PromQL 테스트1건이 실패했고 Docker Build는 실행되지 않았다.
직전 로컬1,497건 PASS를 CI 성공으로 취급하지 않는다. 이전 `submission-verification.json`은 당시 로컬 결과 그대로 보존한다.

실패 경계는 `Issue68UsageBenchmarkKafkaTest`가 Docker Desktop 전용
`host.docker.internal:18068`을 scrape하면서 host 전달을 구성하지 않은 점이다.
Prometheus의 `/-/ready`는 scrape 성공을 의미하지 않는데도, 두 번째 Fake 응답에서 첫 호출 Counter의
scrape를 기다렸다. 연결 단절이면 이 대기가 Fake 응답/실행을 실패시켜 `…STAGE_INCOMPLETE → …GATE_HOLD`로 보인다.
실제 Linux CI 로그는 line221의 예외 타입만 보존했으므로 과거 장애가 DNS 해석 실패인지 라우팅/연결 실패인지까지
단정하지 않는다. 기존 주소를 loopback으로 강제해 끊은 로컬 음성대조에서 같은 실행 HOLD를 재현했다.
Kafka 초기화·모델 품질·실제 OpenAI 장애가 원인이라는 근거는 없다.

수정은 테스트1파일뿐이다. 임의 포트 loopback exporter를 먼저 시작하고
`Testcontainers.exposeHostPorts(port)`와 `host.testcontainers.internal`을 사용한다.
Prometheus 시작 후 **정확한 scrape URL, target1개/health UP/lastError 없음**을 검증한 뒤 Fake 호출을 시작한다.
회귀에서는 기존 Docker Desktop 주소를 의도적으로 사용 불가능하게 유지해 새 경로의 독립성을 확인한다.
공식 연결 계약: [Testcontainers host ports](https://java.testcontainers.org/features/networking/#exposing-host-ports-to-the-container).

원래 네 패널 PromQL·finite p95·정확한 호출/Token 수치 Assertion, 15초 제한은 그대로다.
테스트 삭제/SKIP/무조건 timeout 증가는 없다. 기존 Dashboard와 모니터링 설정도 이번 보완에서 변경하지 않았다.
수정 대상 메서드는 root1건 PASS였지만 aggregate 필터가 Lambda에 전파돼 `No tests found`로 명령 전체는 실패했다.
이후 root `:test`로 실행한 Benchmark/MonitoringBridge/DashboardContract 관련 **16건 PASS/skip0/실패0**를 별도로 확인했다.
최종 전체 회귀·보존 검사는 [ci-portability-verification.json](ci-portability-verification.json),
최신 보존 Head·CI·AI Review는 [Draft PR #69](https://github.com/gpekd5/bobfull-backend/pull/69)를 따른다.
수정 후 `test build --rerun-tasks --no-daemon --console=plain`은 **9m7s SUCCESS/12 tasks 실행**,
root1,486(94 skipped)+Lambda11=**1,497건/실패0/오류0**다. 유료 진입점6개 모두 SKIP이며 Key/유료 승인 변수는 제거했다.
PR 전체 제출 범위는 기존123파일에 이 신규 CI 검증 기록1개를 더한124파일이다. 과거 제출/실측 JSON은 덮어쓰지 않았다.
장부·UNKNOWN·과거 Live/GT/Gate 원본을 수정하거나 이번 Fake 성공을 과거 품질 PASS로 해석하지 않는다.

## 최초 PR 제출 검증 기록 — 2026-10-22

2026-10-22 최종 제출 검증은 [submission-verification.json](submission-verification.json)에 별도로 기록했다.
Key와 모든 `ISSUE68_*` 환경변수를 제거한 자식 프로세스에서
`./gradlew test build --rerun-tasks --no-daemon --console=plain`을 실행했다.
**root1,486건(94 skipped)+Lambda11건, 합계1,497건/실패0/오류0, BUILD SUCCESSFUL(8m58s), 12 tasks 실제 실행**이다.
#68 관련36 suites/303건(8 skipped)도 포함하며, 실제 유료 진입점6개는 모두 SKIP됐다.
과거 root1,474건 및 실제360건 paid opt-in 실행 기록은 원본 시점의 결과로 보존하고,
이번 무과금 테스트 성공을 과거 A1/Control 품질 실패의 PASS로 소급 해석하지 않는다.
제출 대상은 테스트63/Evidence54/로컬 monitoring4/루트 보호·compose2, 합계123파일이다.
JSON·링크·동결 hash·민감정보 제외·Compose/Prometheus 검사와 장부/Production/Dashboard 불변도 검증했다.
제출 Commit SHA는 자기참조로 이 문서에 넣지 않고 연결 PR 본문과 최신 Head Review에 기록한다.
PR은 `Refs #68`로 연결하며 자동 종료 문구를 사용하지 않는다. CI와 AI Review 결과는 PR에서 확인한다.
이번 문서 해석 점검에서는 Key와 모든 Live opt-in 변수를 제거한 자식 프로세스로
`Issue68ReviewSignalIntegrationTest`(MySQL8.4), `AdminModerationReportServiceTest`,
`ChatRoomMemberReportServiceTest`를 재실행했다. **각4건, 합계12 PASS/skip0/실패0**, Gradle test SUCCESS(15s).
HIGH/MEDIUM의2+1=3건 경계, SAFE/LOW의검토 건수 누락, 신고의AI 결과 독립성,
관리자 판단이AI 결과를자동변경하지않음을 기존 테스트로 확인했다. 추가 유료 호출이나 Production 변경은 없다.
실행 명령은 [480관측 해석 문서](usage-benchmark-480-live.md#서비스-영향-해석의-무과금-확인)를 따른다.

## 최초 제출 준비 당시 기록 — 현재 파일 수·최신 검증값 아님

- PR 범위는 #68 테스트 Harness/무과금 안전장치/로컬 Fake monitoring/비식별 Evidence와 Private 제외 규칙이다.
  `src/main` 변경0, Production 설정 변경0, Prompt/Policy/Rule/Model/기본 Batch OFF 불변.
- SizeTimeBatchOwnerLoop 변경은 기존 테스트 전용 class의 failure/HOLD hook뿐이다.
  Production Size/Time 도입이나 새 수집 알고리즘이 아니다.
- 기존 PR #60/#61/#63/#66/#67 파일을 다시 구현하거나 중복 제출하지 않는다.
- 최신 동일 Java 소스의 무과금 전체 회귀는 [N1 Readiness](a1-native-n1-readiness.md)의 **1,451 tests/92 skipped/failures0/errors0,
  BUILD SUCCESSFUL**이다. 이후 A1 Live1건은 Critical Gate로 **1 failure/BUILD FAILED**로 보존한다.
  기술 회귀 성공과 실제 품질 실패를 합쳐 “모든 테스트 PASS”로 표현하지 않는다.
- 이번 최종 정리는 README 색인/종료 계약과 `.gitignore`만 수정한다. Java 및 과거 raw 변경 없이 정적 검증만 수행한다.
  새 전체 회귀·GitHub CI·최신 Head AI Review는 **후속 제출 단계 NOT_RUN**이며 현재 완료했다고 기록하지 않는다.
- 후속 회귀는 Key/모든 Live 승인·Manifest·local monitoring opt-in 환경변수를 제거한 자식 프로세스에서
  관련 테스트와 전체 `test build --rerun-tasks`를 실행한다. 기존 raw 보존을 위해 `clean`은 사용하지 않는다.
  Live opt-in은 SKIP해야 하며 유료 실패를 숨기기 위한 Gate 변경은 하지 않는다.
- 제출 전 파일별 명시 staging, `.env/build/.issue68-live` 제외 및 합성 fixture와 실제 민감정보 구분,
  CI/Review 결과를 최신 보존 Head 기준으로 확인한다. 아직 staging/Commit/Push/PR/Issue 변경 없음.

### 이번 최종 정리의 정적 검증

- 제출 후보101파일: 테스트55/Evidence40/로컬 monitoring4/루트 보호·compose2. staged 파일0.
- JSON25개 syntax, README 로컬 링크38개,101파일 whitespace 및 `git diff --check` PASS.
- Fake 격리 monitoring `docker compose --env-file /dev/null ... config --quiet` PASS. 기동/재기동/유료 전송은 하지 않았다.
- 알려진 API Key/개인키/AWS·GitHub token/Bearer 패턴 미검출. 공개 JSON에서 서비스 메시지·회원·채팅방·event ID 및
  실제 credential 값 필드는 발견하지 않았다. `authorization`라는 승인 검증 결과 객체는 HTTP 인증 헤더/Key와 구분한다.
- `.env/build/.issue68-live` ignore 적용 및 Git tracked/index 미포함 확인. Private 파일34개는 로컬에 그대로 남긴다.
- 시작 시 기존 Evidence/Private74파일과 대조하여 README만 변경, 삭제0/Raw·Private 내용 변경0.
  README 끝의 기존 Phase1 본문은 byte 그대로 보존했다. 승인 Manifest의 테스트/Production69소스 해시도 동일하다.
- journal의12개 hash-chain 및 최신 전체 파일 해시 일치. `.gitignore`는 Git 제외만 적용하며 journal을 수정하지 않는다.
- 신규 전체 회귀/CI/AI Review는 이번 단계에서 수행하지 않았다. 위 정적 PASS를 품질 Gate PASS로 해석하지 않는다.

## 후속 Issue 제안 (아직 생성하지 않음)

제목: **[project][experiment] AI Moderation 위험도 경계 및 Critical 보호 품질 개선 검증**

- 목적: 비용 최적화와 분리해 Native 공통 오류 및 Batch 추가 회귀를 함께 평가한다.
- 관리자 검토 결정과 향후 확보할 실제 제재 결과를 Human Feedback으로 평가하되 현재 위반 여부 기록을
  실제 제재 완료나 Risk GT로 자동 간주하지 않는다. 실제 제재 집행·피드백 수집 경로는 별도 확인/설계 대상이다.
- 사용자 신고와 AI 결과의 교차 분석으로 미탐 후보를 찾되 미신고=정상 GT로 간주하지 않는다.
  신고 표본 편향, 비식별화 및 Human 재심을 포함한다.
- 고위험 의심 항목의 별도 검증/재분류는 **후속 계획**이다. 선별 기준/추가 비용/개인정보/재시도/정책은
  사전 Human 승인 대상이며 현재 구현되거나 모든 FN을 방지한다고 표현하지 않는다.
- HIGH/MEDIUM 경계 개선과 추가 FN 회귀 검증 후 Poll-Batch의 운영 활성화 여부를 재판단한다.
  Poll은 우선 도입 검토 후보이며 운영 채택 완료가 아니다.
- 기존 GT/Policy/Gate는 보존. 정책 의미 판단이 필요하면 Human 결정으로 별도 버전·범위를 확정하며
  과거 GT/점수를 소급 바꾸지 않는다.
- 신규 합성 위협/조건부·간접 표현/정상 경고/농담·친근 표현/PI/SPAM 보호 사례를 Human이 검토·동결한다.
  기존80/진단20은 개발·회귀 자료이며 신규 블라인드 평가로 쓰지 않는다.
  기존 미확정40건 및 A1 추가 합성20건도 Human 확정 전 공식 평가에 편입하지 않는다.
- 같은 시점 Control을 포함해 Native 공통 품질부터 비교하고, 통과 후 같은 후보의 Batch 형식·크기·구성·순서 회귀를 검증한다.
  특정 실패 Case 원문·정답 암기, 단순 재호출로 좋은 결과 선택, 미승인 자동 HIGH 보정은 제외한다.
- Result/Category/Risk/FP/FN/Critical 보호/Review Target와 경계3건 검토 신호, 정책 위반·UNRESOLVED·반복 안정성을 분리한다.
- 유료 실행은 새 Prompt/GT metadata/Matrix/Gate/비용/요청 상한/중단 계약을 Human이 먼저 승인한다.
  #68의 기존 journal·UNKNOWN·HOLD를 초기화해 승인 범위를 우회하지 않는다.
- 완료는 후보 채택 또는 미채택의 근거 있는 판정이다. Production 반영/Batch 활성화/#55 종료는 별도 Human 결정이다.
- Kafka Retry/Size-Time/Grafana 재설계·AWS·모델 임의 교체는 포함하지 않는다.

### 최초 제출 후보 목록 (당시101파일, 이후 Benchmark 추가 전)

아래는 최초 제출 준비 당시 작업 트리 기준 후보이며 당시 staging/Commit하지 않았다.
`.gitignore`를 포함한101파일은 이후 추가된 Usage Benchmark 파일을 포함하지 않는 역사적 목록이다.

#### 저장소 보호 및 기존 로컬 구성 (2파일)

```text
.gitignore
docker-compose.monitoring.yml
```

#### 로컬 모니터링 (4파일)

```text
ops/local-monitoring/issue68-dashboard/README.md
ops/local-monitoring/issue68-dashboard/docker-compose.yml
ops/local-monitoring/issue68-dashboard/prometheus.yml
ops/local-monitoring/prometheus/prometheus.yml
```

#### 테스트 코드 (55파일)

```text
src/test/java/com/bobfull/admin/infrastructure/repository/query/Issue68ReviewSignalIntegrationTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68A1N1Plan.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68A1N1SafetyTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68A1N1Session.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68Budget.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68BudgetTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68DashboardContractTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68EvaluationContract.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68EvaluationContractTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExecutionGate.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExpandedBudgetTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExpandedHttpSafetyTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExpandedLiveSafetyTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExpandedLiveSession.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExpandedPlan.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExpandedPlanTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ExpandedPreparationSession.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68FakeOpenAiEndpoint.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68GateV2.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68GateV2Test.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68HttpBoundary.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68HttpBoundaryTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68LiveReadinessTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68LiveResumeTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68LiveSession.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68MonitoringBridge.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68MonitoringBridgeTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68O0Plan.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68O0PlanTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68O0PreparationTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68O0SafetyTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68O0Session.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68OpenAiContext.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ResponseUsage.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ResponseUsageTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ReviewGateDraft.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ReviewGateDraftTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68RiskBoundaryPrompt.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68RiskBoundaryPromptTest.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68SdkUsage.java
src/test/java/com/bobfull/chat/infrastructure/ai/Issue68UsageAccountingTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68A1N1Execution.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68A1N1KafkaSafetyTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68A1N1LiveTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68ExpandedKafkaPreparationTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68ExpandedLiveKafkaSafetyTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68ExpandedPilotExecution.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68ExpandedPilotLiveTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68HoldObservabilityTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68LiveSmokeTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68O0KafkaPreparationTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68O0LiveTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68OpenAiKafkaHarness.java
src/test/java/com/bobfull/chat/infrastructure/kafka/Issue68OpenAiKafkaIntegrationTest.java
src/test/java/com/bobfull/chat/infrastructure/kafka/SizeTimeBatchOwnerLoop.java
```

#### Evidence (40파일)

디렉터리: `docs/110-records/evidence/v3/68-openai-poll-batch-verification/`.

```text
README.md
a1-native-n1-approval-proposal.json
a1-native-n1-live-results.json
a1-native-n1-live.md
a1-native-n1-readiness.md
critical-hold-fake-monitoring.json
critical-hold-observability.md
dashboard-panel-fake-results.json
dashboard-panel-verification.md
dashboard-runtime-export.json
expanded-live-readiness-results.json
expanded-live-readiness-verification.json
expanded-live-readiness.md
expanded-pilot-approval-manifest.json
expanded-pilot-live-results.json
expanded-pilot-live.md
expanded-preparation-results.json
expanded-preparation-verification.json
expanded-preparation.md
gate-v2-metadata-v1.json
human-gt-gate-v2-review.md
human-gt-gate-v2-verification.json
live-readiness-results.json
live-readiness.md
live-resume-usage-v1-audit.json
live-resume-usage-v1-results.json
live-resume-usage-v1-verification.json
live-resume-usage-v1.md
live-smoke-results.json
live-smoke-stop-evidence.json
o0-approval-safety-verification.json
o0-approval-safety.md
o0-fake-rehearsal-extract.json
o0-preparation-verification.json
o0-readiness.md
risk-boundary-a1-freeze.json
risk-boundary-a1.md
usage-accounting-results.json
usage-accounting.md
verification-results.json
```

---

## 아래는 Phase 1 당시 원문 보존 기록

아래의 “Phase 1만 수행”, $0.50 계획, live 미검증 및 880건 Matrix 등은 **당시 단계의 계약·결과**다.
현재 전체 상태나 새 실행 승인으로 해석하지 않는다. 최신 상태는 위 최종 판정과 각 후속 원본을 따른다.
이전 본문은 내용 그대로 남기며 과거 실패·미관측을 새 코드의 성공으로 소급 변경하지 않는다.

# Issue #68 — 실제 Provider 검증 전 무과금 Harness

## 상태와 검증 범위

- **Phase 1만 수행. 실제 OpenAI API 호출/유료 평가 미승인·미실행.**
- 실제 Consumer → 실제 Spring AI Adapter/SDK → **loopback Fake HTTP endpoint** → Validation → 실제 JPA/MySQL → Kafka offset을 검증한다.
- Fake endpoint는 기존 GT를 echo하거나 의도적인 오류를 반환한다. 정답률/Token/지연을 실제 모델 품질·사용량·비용·운영 성능으로 해석하지 않는다.
- Production 코드/설정/Prompt/Policy/Rule/Model/기본 Batch OFF 및 #64 Retry 계약은 변경하지 않았다.
- 기존 개발 DB·AWS·실사용자 자료·실제 API Key를 사용하지 않는다. 신규40건은 Human GT 미확정으로 제외한다.
- Commit/Push/PR/Issue Close 및 운영 활성화는 이번 단계에서 수행하지 않는다.

## 계약과 실행 기준

- Issue: [#68](https://github.com/gpekd5/bobfull-backend/issues/68), 상위 [#55](https://github.com/gpekd5/bobfull-backend/issues/55).
- 기준 develop/실행 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`.
- 브랜치: `feature/68-openai-poll-batch-verification`.
- 새 테스트는 미커밋 상태에서 실행했다. 원본의 executionHead를 미래 Commit SHA로 소급 교체하지 않는다.
- 실행 시각·실제 Git dirty 상태·실행 테스트 소스 SHA-256은 원본 결과에 기록한다.
- Primary: 실제 DB transaction afterCommit 및 offset 대응, 예산 전송 전 차단, 단계별 품질 Gate.
- Secondary: 실제 poll/LLM 요청 구성, 논리 요청/HTTP exchange, 요청별 Usage/latency, Lag 표본.
- 안전 확인: 기존 Provider/Rule/트랜잭션/Retry/DLT 보존, 원문·인증정보·DB 식별자 미저장, 기존 Evidence/raw 미수정.

## GT 동결

- `heldoutCasesPolicyV2()` 80건과 기존 진단20을 재사용한다. **과거 실험에 사용한 데이터이며 신규 블라인드 평가가 아니다.**
- Policy v1 Dataset/Challenge/freeze를 수정하지 않는다. 현재 v2 override 15건도 변경하지 않는다.
- 전체80은 SAFE38/FLAGGED42, GT HIGH10, 단일 Rule1/LLM79다. 진단20은 모두 Rule miss다.
- GT manifest는 ID·본문 SHA-256·expected tuple을 canonical 정렬하여 동결한다. 본문은 결과 파일에 저장하지 않는다.
- v2 GT SHA-256: `2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b`.
- Quality Gate 버전: `issue68-human-gate-v1`. 실제 구현과 소스 해시는 결과에 보존한다.
- Prompt: `moderation-prompt-v3-short-fragment-boundary`, Policy: `moderation-policy-v2`, model 설정: `gpt-4o-mini`.
- GT HIGH: PROFANITY-02/08/14/15/16, SPAM-08/09/10/11/16. SAFE-17 등 기존 FP 사례도 원래 GT 그대로 추적한다.

## 단계별 평가 Matrix — 미실행 계획

| 단계 | 입력/유입 | 각 모드 반복 | Native 요청 | Poll 요청 |
|---|---|---:|---:|---:|
| diagnostic-underfill | 진단20, 이전 DB 완료·Lag0 후 다음 발행 | 1 | 20 | 20 Native |
| batch-pilot | 같은 진단20, prefill/Burst | 1 | 20 | 2~20 |
| heldout-O0 | 80건, Seed55, prefill/Burst | 3 | 237 | 24~237 |
| heldout-O1 | 같은80, Seed68, prefill/Burst | 2 | 158 | 16~158 |
| 합계 | 880건, 공통 Rule10/LLM870 | | 435 | 62~435 |

- 최대1,000건 승인 범위 안에 Pilot도 포함한다. O1은 2회이며 3회라고 표기하지 않는다.
- 정상 예상 논리 요청은 497~870이다. 실제 poll 분할/Rule/Skip에 따라 달라지며, 최대10을 항상 채운다고 가정하지 않는다.
- 순차 진단은 **Batch ON에서도 Native만 호출**한다. Batch 계약 점검을 대신하지 않는다.
- Pilot은 실제 Batch 요청이 발생했음을 별도로 확인한다. 운영 품질을 입증하는 최종 표본이 아니다.
- 이전 단계의 문제를 확인한 상태에서 다음 유료 단계를 자동 실행하지 않는다. 전체 Matrix 자동 실행/유료 진입점은 이번 코드에 없다.
- mode 순서 교차, 입력·GT·seed·옵션 고정, 요청별 실제 구성/중립ID 순서/본문 해시를 보존한다.
- Dataset seed는 입력 순서용이다. Provider sampling seed를 새로 설정하거나 응답 결정성을 보장하지 않는다.
- partition3에서 입력 seed만 같아도 poll/Provider 구성은 동일하지 않을 수 있다. 같은 요청 해시의 반복과 다른 구성의 결과를 분리해야 한다.

## 품질 Gate

- FN, GT HIGH 미달(SAFE/LOW/MEDIUM), Policy 금지 조합, UNRESOLVED를 별도 기록한다.
- 같은 Case의 Native 정답 SAFE → Poll FLAGGED는 새 FP, Native 정답 → Poll 오답은 regression으로 기록한다.
- Native에도 있는 오류를 공통 문제로 집계하며 Batch의 절대 품질 미달을 정상으로 바꾸지 않는다.
- Result/Category/Risk/Actionability exact, TP/FP/FN/TN, P/R/F1을 집계한다. UNRESOLVED는 TN/SAFE 또는 정답으로 세지 않는다.
- 전체 Accuracy 상승 또는 반복 tuple 일치만으로 품질 유지/운영 채택을 선언하지 않는다.
- Mapping/Parse/Validation/Persistence/DB-offset 오류는 별도 Hold다. 재처리로 DB가 정상 복구돼도 기술 오류 관측을 지우지 않는다.
- 정상 형식의 FN은 실제 코드상 SAFE로 저장될 수 있다. 이 경우 Retry/DLT로 고쳐진다고 가정하지 않고, 평가 Gate가 다음 단계를 막는다.
- Gate는 추가 평가 진행 조건이지 운영 활성화 승인이나 절대 품질 합격 판정이 아니다.

## 테스트 환경과 관측 경계

| 항목 | 구성 |
|---|---|
| Kafka | Testcontainers cp-kafka7.7.1, source/DLT 각각3partitions, 실행별 격리 Topic/group |
| DB | Testcontainers MySQL8.4, fixture DB만 create-drop, 실제 Repository/JPA/auditing |
| Consumer | concurrency1/max.poll.records10/auto-commit=false |
| fetch/poll | fetch.min.bytes1/fetch.max.wait100ms/poll-timeout100ms |
| ACK | 기존 Native RECORD / Poll BATCH |
| Retry | 기존 Kafka 최대3/backoff1000ms, 실제 SDK maxRetries3/기본 timeout60초 |
| Provider | 실제 Port/Adapter/ChatClient/auto-configured OpenAiChatModel, loopback HTTP만 허용 |
| Fake 실패 응답 | Retry-After 헤더는 빠른 fixture 실행용이며 운영 Provider backoff 실측이 아님 |
| 입력 | 승인된 합성 Dataset만, 독립 room/sender로 Split 문맥의 우연한 결합 방지 |

- 수신: ConsumerInterceptor.onConsume, 같은 poll은 같은 첫 수신 시각. 재전달 횟수 별도.
- DB: TransactionInterceptor 안쪽 afterCommit. flush 반환/Repository 반환도 따로 기록하며 순서를 검사한다.
- Offset: ConsumerInterceptor.onCommit과 Admin committed/end 조회. DB 성공과 Kafka commit을 같은 것으로 취급하지 않는다.
- Lag: 약100ms Admin 표본. 두 RPC는 원자적 snapshot이 아니며 표본 최고값이 절대 peak는 아니다. sampler는 진행 중 RPC를 정상 종료 후 정리한다.
- 원본 Kafka partition/offset과 DB 결과는 테스트 내부로 매핑한다. 저장본은 합성 Case ID/partition/offset만 사용하고 실제 DB PK/원문/회원·채팅방 ID를 내보내지 않는다.
- Provider 요청은 기존 중립 ID만 전송한다. 원본 요청은 메모리에서 검사하고 SHA-256/Case 구성/출력cap/tokenizer 추정만 저장한다.
- Batch Usage는 요청 단위다. 각 DB 메시지 Token에 복제하거나 임의 배분하지 않는다.
- 기존 `bobfull.ai.moderation.e2e` Timer는 생성→처리 후 의미다. 수신→DB commit으로 대체하지 않는다.

## 예산 보호 — 내부 장부 검증과 실제 청구를 구분

- #68 전용 계획 한도 $0.50, 과거 #55 누적액과 분리한다. **유료 호출 승인은 아니다.**
- nano-USD 정수: input Token당150/output600. Cache 할인은 보호 계산에서 사용하지 않는다.
- 매 실제 HTTP exchange 전 `128,000 input + 요청 output cap`의 최대액을 예약·journal fsync한 뒤 전송한다.
- SDK retry뿐 아니라 하위 transport follow-up도 network interceptor에서 매번 예약한다. application interceptor 관측만으로 실제 exchange를 세었다고 주장하지 않는다.
- redirect는 금지한다. 기존 연결 재시도와 SDK3은 유지하되 하위 exchange도 장부를 통과한다.
- Usage가 유효하면 할인 미반영 비용으로 정산한다. Usage 없음/깨짐/음수/상한 초과/합계 불일치는 전액 예약을 유지한다.
- 오류 응답/전송 후 응답 유실을 비용0으로 만들지 않는다. Cache 미확인은0 cache hit으로 확정하지 않는다.
- 장부 동시 접근은 동기화한다. journal 생성은 CREATE_NEW이며 덮어쓰기·자동 재개·중복 정산·위조 Ticket을 거절한다.
- 장부 실패/예산 부족 latch 이후 추가 exchange를 전송하지 않는다. 단계별 Context가 바뀌어도 같은 wallet을 공유해야 한다.
- 단위 검증: cap1280/Usage불명25회는 $0.4992 예약, 26번째 거절. 이는 실제 지출이 아니라 Fake 내부 예약 검증이다.
- 이 장치는 **고정 공개 단가·모델 한도 가정 아래 해당 실행의 보수적 추정 장부 상한**이다. OpenAI 계정 전체 청구액, 세금, 다른 실행/프로세스, 가격·Provider 계약 변경까지 보장하는 기능이 아니다.
- 현재 전송 경계는 127.0.0.1의 fixture 전용이다. live endpoint는 생성 단계에서 거절한다. 실제 TLS/계정/실제 Provider에서 검증한 것으로 확대하지 않는다.
- 실제 과금 한도를 보장할 수 없는 조건이 남으면 유료 실행을 시작하지 않는다. live 경계/단가/원본 보관/단일 journal 및 중단 후 복구 계약을 승인 직전에 다시 확인해야 한다.

## 무과금 Token·비용 사전 추정

실제 Production System Prompt/Batch 지시문/BeanOutputConverter Schema와 고정 입력을 O200K_BASE로 센 뒤 요청당 framing 여유128을 더한다.
**Provider Usage/실제 청구 상한이 아니다.** 최저 요청형과 전부 underfill Native인 형을 비교한 계획 추정이며 실제 poll 형태를 예측한 결과가 아니다.

| 단계 | 추정 input min~max | output cap 합계 | 미할인 추정 비용 min~max |
|---|---:|---:|---:|
| underfill | 41,638 | 5,120 | $0.00931770 |
| Pilot | 23,728~41,638 | 5,120 | $0.00663120~$0.00931770 |
| O0 | 281,913~493,770 | 60,672 | $0.07869015~$0.11046870 |
| O1 | 187,942~329,180 | 40,448 | $0.05246010~$0.07364580 |
| 합계 | 535,221~906,226 | 111,360 | $0.14709915~$0.20274990 |

- Retry 미포함·출력 cap 전체 사용 기준이다. 정상 응답의 실측 비용이 아니다.
- 고정 Provider 실패 단계의 Kafka3×SDK4를 단순 적용하면 계획 비용도 최대 약$2.433가 된다. $0.50 내 완주를 보장하지 않으며 예산 차단이 먼저 실행될 수 있다.
- 12배 계산은 재균형/재시작/실패 단계 전환/하위 follow-up 전체의 전역 HTTP 상한이 아니다. 원본에는 실제 exchange를 기록한다.
- 공개 단가: input $0.15/cached input $0.075/output $0.60 per1M, [공식 모델 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini). 동기 Micro-batching에 Batch API 할인을 적용하지 않는다.
- 공식 [deprecations](https://developers.openai.com/api/docs/deprecations)에는 해당 snapshot 종료 예정일2027-01-14가 있다. 모델은 변경하지 않았다. **계정별 실제 가용성/API 응답은 미확인**이며, 확인을 위한 API 호출도 하지 않았다.

## 실행·결과

관련 명령:

```bash
env -u OPENAI_API_KEY ./gradlew :test --tests '*Issue68*Test' \
  --tests '*ChatModerationBatch*' --tests '*ChatModerationServiceTest' \
  --tests '*ModerationRulePolicyTest' --tests '*ChatModerationProviderContextTest' \
  --tests '*SpringAiModerationBatchAdapterTest' --tests '*SpringAiRuntimeMetricsIntegrationTest' \
  --tests '*ModerationHeldoutDatasetTest' --console=plain --no-daemon
env -u OPENAI_API_KEY ./gradlew test build --rerun-tasks --console=plain --no-daemon
git diff --check
```

- 관련 회귀139건 PASS 뒤 최종 Gate 보존 테스트1건과 결과 metadata를 보완했다.
- 최신 전체 `test build --rerun-tasks`: **SUCCESS in4m18s, 12 tasks 실제 실행**.
- root1,211 tests / skipped86 / failures0 / errors0, Lambda11 / skipped0 / failures0 / errors0. 합계 **1,222 tests / skipped86 / failures0 / errors0**. Lambda도 이번에 재실행했다.
- 신규28건 PASS: Budget7/Contract5/HTTP6/Kafka-MySQL10. 최신 전체 실행에 포함된 관련 subset140건도 failures0/errors0이다.
- 전용 대규모 `kafka-evidence` benchmark와 실제 OpenAI Evaluation은 실행하지 않았다. CI/PR은 이번 단계에서 진행하지 않았다.
- 원본5개와 JUnit 집계는 [verification-results.json](verification-results.json)에 보존한다. 모든 실행 소스 해시를 현재 파일과 대조해 동일함을 확인했다.
- 개발 중 일반 Configuration의 Repository scan 간섭으로 관련138건 중21건이 실패했다. TestConfiguration으로 격리했고 Production 수정 없이 기존 기동 회귀를 복구했다.
- 후속 실행의 신규 fixture 건수 변경 위치 오류2건/Lag 관측 종료 문제1건은 FAIL로 보존한다. fixture 입력/기대 건수와 sampler의 graceful 종료를 수정한 뒤 관련139건 PASS를 확인했다. 중간 실패를 Production 결함 또는 PASS로 바꾸지 않는다.

### 실제 Fake 관측 — 모델 품질/운영 성능이 아님

| 검증 | 실제 관측 |
|---|---|
| 순차 진단20 × 두 모드 | 각각 Native20/Batch0, DB20/Lag0/DLT0/조기commit0 |
| Batch Pilot20 | Batch10건×2회, DB20/Lag0/DLT0. 요청별 구성/해시와 DB Token NULL 확인 |
| 전체80 공유 장부 | Native79요청, Poll8요청(10건×7/9건×1), 양쪽 DB80/Lag0/조기commit0, Rule1은 요청에서 제외 |
| SDK 429→200 | 논리1/HTTP2. 첫429의 Usage 불명 예약 유지 |
| SDK 재시도 예산 차단 | 첫 실패 뒤 다음 exchange가 서버에 도달하지 않음 |
| 하위503 follow-up 차단 | 다음 exchange 전 새 예약 필요. 예산 부족 시 서버 수신1 유지 |
| 전체 매핑 실패6건 | 공유 논리3/HTTP3, 항목별 참여3, DLT6/최종FAILED6/Lag0 |
| 지속 Provider 실패2건 + DLT후 DB 첫실패 | Batch논리3/HTTP12, recovery 중 Provider 추가0, DLT3/최종FAILED2/Lag0 |
| Parse/Validation 실패 | UNRESOLVED/최종FAILED와 DLT·Lag를 구분하여 확인 |
| 정상 형식의 의도적 FN | SAFE 저장/offset 진행/DLT0이지만 FN·GT HIGH Gate로 다음 단계 Hold |
| 매핑 실패후 정상 재처리 | DB 완료/Lag0이어도 TECHNICAL_FAILURE_OBSERVED를 남겨 다음 단계 Hold |

전체80의 Fake 정답은 GT echo 결과다. 정확도100%나 실제 OpenAI 비용/Token 절감으로 인용하지 않는다.
원본은 HTTP Usage를 요청별로 한 번만 저장한다. 공유 장부 원본의 HTTP87건은 Native79+Poll8 전체이며 두 모드에 각각87건으로 중복 집계하지 않는다.

Native/Poll 모두 기존 Spring Listener를 사용한다. 이번 정상 표본은 프로세스 강제 종료·재균형·실제 Provider 장애 분포 검증을 대신하지 않는다.

## 실제 호출 전 남은 Gate

1. Human의 명시적인 **유료 호출/단계별 진행/중단 기준/금액 승인**.
2. live 전송 경계의 같은 예약·redirect·하위 retry·TLS·계정/모델·단가 검증. 현재 loopback 검증만으로 live 예산 보장을 선언하지 않는다.
3. 고정 journal 한개·과거 파일 존재 시 중단·재시작 후 재승인. 자동 예산 초기화/자동 재개 금지.
4. 실제 요청 크기/구성 확인 후 비교. Fake 충전율과 Token을 OpenAI 결과로 대신 사용하지 않는다.
5. 실제 FN/FP/HIGH 하향·품질 및 OpenAI→MySQL E2E는 미실행. 현재 구현을 운영 활성화나 #55 종료 근거로 사용하지 않는다.

## 관련 기록

- [#48 GT/Evaluation](../48-ai-moderation-evaluation/README.md)
- [#56~#58 기존 실험](../55-ai-moderation-efficiency/README.md)
- [#62 시간·Commit 관측](../62-kafka-poll-batch-measurement/README.md)
- [#64 그룹 Retry/recovery-only](../64-batch-retry-amplification/README.md)
- [#65 Size/Time 비교·Production 미도입](../65-size-time-batch-comparison/README.md)

기존 Evidence/raw는 소급 변경하지 않았다.
