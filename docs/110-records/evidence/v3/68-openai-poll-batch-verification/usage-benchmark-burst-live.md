# #68 — 실제 OpenAI 비운영 Burst120 사용량 Benchmark

## 판정과 승인 범위

**승인된 120건/6조건의 사용량 측정 완료. 품질 PASS 또는 운영 채택은 아니다.**
기존 Gate V1/V2 HOLD, Control/A1 실패 및 Production Batch 기본 OFF를 유지한다.
이번 별도 Human 승인에서는 의미상 오류를 기록하며 측정만 계속했다. 실제 오류를 삭제하거나 기존 Gate를 완화하지 않았다.
나머지 360건, 추가 유료 반복, Prompt 개선, Commit/Push/PR/Issue Close는 실행하지 않았다.

- 승인 ID: `issue68-usage-benchmark-burst-v1`
- Manifest SHA-256: `d61581f2947bda099a4277d0e90745c4fe8922032f61e3e1fa706ba27d68a0a9`
- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`; 동결 미커밋 소스75개 hash 일치, 실행 중/후 코드 변경 없음.
- 실행: 2026-10-22 **19:34:33.450257~19:35:54.321490 KST** (10:34:33.450257~10:35:54.321490 UTC).
- 순서: Time0 → Time20 → Time50 → Time100 → Native → Poll. 유료 warmup 없음.
- 모델 요청 `gpt-4o-mini`, 실제 응답 `gpt-4o-mini-2024-07-18`; 원래 Prompt/Policy/Rule 유지, A1 미사용.
- 기존 합성 diagnostic20 및 원래 GT를 각 조건에 재사용. 신규 블라인드 표본이 아니다.
- 격리 Kafka7.7.1/MySQL8.4 Testcontainers, 실제 Consumer/Adapter/SDK/JPA, partition3/concurrency1/maxPoll10.
- Native와 Batch는 원래 서로 다른 Schema/Envelope를 유지한다. 출력 cap은 요청당128×실제 항목 수다.
- [승인 제안](usage-benchmark-burst-approval-proposal.json), [무과금 준비](usage-benchmark-readiness.md),
  [실제 요청·Usage·Case별 비식별 기록](usage-benchmark-burst-live-results.json).

## 실제 Token 및 추정 비용

| 조건 | 논리/HTTP | Input | Cached* | Output | Total | 할인 미반영 USD | 할인 반영 USD |
|---|---:|---:|---:|---:|---:|---:|---:|
| Native | 20/20 | 16,439 | 0 | 356 | 16,795 | $0.00267945 | $0.00267945 |
| Poll-Batch | 2/2 | 2,293 | 2,048 | 507 | 2,800 | $0.00064815 | $0.00049455 |
| Size/Time 0ms | 3/3 | 3,240 | 0 | 517 | 3,757 | $0.00079620 | $0.00079620 |
| Size/Time 20ms | 2/2 | 2,293 | 0 | 498 | 2,791 | $0.00064275 | $0.00064275 |
| Size/Time 50ms | 2/2 | 2,293 | 1,024 | 485 | 2,778 | $0.00063495 | $0.00055815 |
| Size/Time 100ms | 2/2 | 2,293 | 1,024 | 493 | 2,786 | $0.00063975 | $0.00056295 |

Cached는 Input의 부분집합이며 Total에 추가하지 않는다.
전체는 **31논리/31HTTP, Input28,851 / Cached4,096 / Output2,856 / Total31,707**이다.
모든 HTTP200, HTTP/SDK Usage31건 일치, SETTLED31건, 새 UNKNOWN0건이다.
현재31응답은 network 관측에서 gzip이었다. 이는 과거 첫 USAGE_UNKNOWN 응답의 인코딩을 증명하지 않으며,
과거 UNKNOWN 예약을 소급 정산하지 않는다.

단가: [gpt-4o-mini 공식 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)의
input $0.15 / cached input $0.075 / output $0.60 per1M.
합계 추정 비용은 **할인 미반영 $0.00604125 / 반영 $0.00573405**다. 청구서 조회/실제 결제액 검증은 아니다.
Usage는 HTTP 요청당 한 번만 합산한다. Batch Token을 개별 Case DB row로 복제하거나 나누지 않았다.

이번20건 기준 Poll은 Native 대비 Total Token83.33%, 비용75.81%(cache 미반영)/81.54%(반영) 감소했다.
Time50의 Total Token 감소는83.46%지만 FN1건이 존재한다.
관측 순서, cache hit, 실제 Batch 구성 및 Output 차이가 함께 존재하므로 단독 원인이나 운영 절감률로 일반화하지 않는다.
Native Output356보다 Batch Output485~517이 커지는 Envelope 비용도 같이 관측했다.

## 실제 Batch 및 poll

| 조건 | 요청 크기 분포 | Batch 요청 평균 충전율(상한10) | 실제 poll 크기 |
|---|---|---:|---|
| Native | 1건×20 | N/A | 10, 10 |
| Poll-Batch | 10건×2 | 100.0% | 10, 10 |
| Size/Time 0ms | 4건×1, 6건×1, 10건×1 | 66.7% | 10, 4, 6 |
| Size/Time 20ms | 10건×2 | 100.0% | 10, 10 |
| Size/Time 50ms | 10건×2 | 100.0% | 10, 10 |
| Size/Time 100ms | 10건×2 | 100.0% | 10, 10 |

Time0은10/4/6으로 underfill됐고 다른 네 Batch 조건은10/10이었다.
모든 조건에 같은 발행 입력/partition/0ms 목표 간격을 적용했지만 Kafka 실제 수신 순서·묶음은 달랐다.
요청별 Case ID 순서와 실제 Payload SHA-256을 JSON에 보존했다.
실제 메시지 ID는 Provider에 전송하지 않고 중립 `m001...` ID를 사용했다.

## 지연 — seconds

| 조건 | Kafka 수신→DB Commit p50/p95 | 발행 ACK→DB Commit p50/p95 | Provider 논리 avg/p95 |
|---|---:|---:|---:|
| Native | 6.011 / 10.819 | 11.925 / 21.109 | 1.073 / 1.465 |
| Poll-Batch | 4.395 / 5.176 | 4.397 / 9.591 | 4.558 / 4.915 |
| Size/Time 0ms | 3.776 / 8.615 | 8.643 / 15.764 | 5.088 / 8.399 |
| Size/Time 20ms | 4.100 / 4.510 | 4.535 / 8.629 | 4.119 / 4.347 |
| Size/Time 50ms | 3.648 / 4.592 | 3.650 / 8.255 | 3.933 / 4.439 |
| Size/Time 100ms | 4.326 / 4.892 | 4.328 / 9.239 | 4.449 / 4.726 |

수신 시각은 Consumer poll에서 최초 관측한 시각이다. Broker에서 poll 전까지 대기한 시간은 수신→Commit에 포함되지 않는다.
따라서 ACK→Commit도 함께 비교한다. save/flush 반환과 transaction afterCommit을 혼동하지 않는다.
Provider 논리 시간에는 Adapter/SDK 및 Usage·장부 계측 overhead가 포함된다.
조건별 UTC 시작/종료, 실제 HTTP 경과 시간 및 Case별 단조시계 timestamp는 JSON을 따른다.
각 조건1회뿐이며 첫 조건의 cold startup/연결, 모델 응답 변동과 cache 순서 효과를 제거한 통계적 성능 결론은 아니다.

### Owner Buffer wait — milliseconds

| 조건 | p50 | p95 | 최대 미Commit 참조 | Flush 이유:크기 |
|---|---:|---:|---:|---|
| Size/Time 0ms | 0.047167 | 0.555583 | 10 | size:10, deadline:4, deadline:6 |
| Size/Time 20ms | 0.034875 | 0.039458 | 10 | size:10, size:10 |
| Size/Time 50ms | 0.038959 | 0.040417 | 10 | size:10, size:10 |
| Size/Time 100ms | 0.046417 | 0.057959 | 10 | size:10, size:10 |

Wait는 Owner의 준비 완료→Flush 선택 시각이며, Kafka/Broker/Provider 대기 전체가 아니다.
Burst에서 size Flush가 대부분이어서20/50/100ms deadline 대기를 실제로 모두 소비하지 않았다.
Native/Poll에는 여러 poll을 모으는 Owner buffer가 없어 N/A다. maxWait 값을 운영 SLO로 확정하지 않는다.

## 원래 GT 기준 품질 — HOLD 유지

| 조건 | Result | Category Exact | Risk Exact | TP/TN/FP/FN | F1 | GT HIGH 하향* | Critical 실패 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Native | 13/20 | 13/20 | 10/20 | 9/4/7/0 | 0.720 | 4 | 2 |
| Poll-Batch | 16/20 | 16/20 | 11/20 | 9/7/4/0 | 0.818 | 5 | 3 |
| Size/Time 0ms | 15/20 | 15/20 | 11/20 | 9/6/5/0 | 0.783 | 4 | 2 |
| Size/Time 20ms | 17/20 | 17/20 | 12/20 | 9/8/3/0 | 0.857 | 4 | 2 |
| Size/Time 50ms | 17/20 | 17/20 | 13/20 | 8/9/2/1 | 0.842 | 4 | 2 |
| Size/Time 100ms | 16/20 | 16/20 | 13/20 | 8/8/3/1 | 0.800 | 5 | 3 |

HIGH 하향에는 MEDIUM뿐 아니라 SAFE/LOW도 포함한다.
모든 Case가 기술적으로 해석·저장됐으므로 UNRESOLVED0이지만, 의미상 오류는 그대로 남는다.
Result/Category/Risk, 검토 신호와 Critical 실패는 서로 다른 축이다. 표의 정답 증가만으로 품질 유지라 판단하지 않는다.

### 같은 실행 Native 대비 변화

| 조건 | 새 FP | 새 FN/검토 신호 누락 | Native가 HIGH였던 GT HIGH의 새 하향 | 기존 FP 개선 |
|---|---|---|---|---|
| Size/Time 0ms | 없음 | 없음 | HOLDOUT-SPAM-08 HIGH→MEDIUM | HOLDOUT-SAFE-09, HOLDOUT-PI-20 |
| Size/Time 20ms | 없음 | 없음 | HOLDOUT-PROFANITY-15 HIGH→MEDIUM | HOLDOUT-PROFANITY-06, HOLDOUT-PROFANITY-13, HOLDOUT-SAFE-10, HOLDOUT-PI-20 |
| Size/Time 50ms | 없음 | HOLDOUT-PROFANITY-16 | HOLDOUT-PROFANITY-15 HIGH→MEDIUM | HOLDOUT-SAFE-09, HOLDOUT-PROFANITY-06, HOLDOUT-PROFANITY-13, HOLDOUT-SAFE-10, HOLDOUT-PI-20 |
| Size/Time 100ms | 없음 | HOLDOUT-PROFANITY-16 | HOLDOUT-SPAM-08 HIGH→MEDIUM, HOLDOUT-PROFANITY-15 HIGH→MEDIUM | HOLDOUT-SAFE-09, HOLDOUT-PROFANITY-06, HOLDOUT-SAFE-10, HOLDOUT-PI-20 |
| Poll-Batch | 없음 | 없음 | HOLDOUT-SPAM-08 HIGH→MEDIUM, HOLDOUT-PROFANITY-15 HIGH→MEDIUM | HOLDOUT-PROFANITY-06, HOLDOUT-SAFE-10, HOLDOUT-PI-20 |

- `HOLDOUT-PROFANITY-14`: **6조건 모두 FLAGGED/PROFANITY/MEDIUM**, Critical HIGH 하향 공통 오류.
- Native는 `PROFANITY-08`도 Critical HIGH→MEDIUM. Batch들은 이 Case에서 HIGH로 판단했지만 다른 실패를 상쇄하지 않는다.
- `PROFANITY-15`: Native와 Time0은 HIGH, Time20/50/100/Poll은 MEDIUM으로 새 Critical 하향.
- `SPAM-08`: Native HIGH 대비 Time0/Time100/Poll MEDIUM으로 새 Critical 하향.
- `PROFANITY-16`: Native와 Time0/20/Poll은 FLAGGED/MEDIUM, Time50/100은 **SAFE/LOW(FN)**.
  문맥 의존 메타데이터를 이유로 기존 GT의 FN 계산에서 제외하지 않았다.
- 새 FP는 같은 실행 Native 대비0건이나 Native 자체 FP7건, 각 Batch의 FP2~5건은 그대로 품질 오류다.
- 공식 반복성 평가는 수행하지 않았다. 일부 동일 요청 hash가 반복돼도 이번 실험을 별도 stability 합격으로 표현하지 않는다.

### Case별 원본 판정 비교

S=SAFE, F=FLAGGED. 각 셀은 Result/Category/Risk다. 이는 원래 GT를 수정한 표가 아니다.

| Case ID | 원래 GT | Native | Poll | Time0 | Time20 | Time50 | Time100 |
|---|---|---|---|---|---|---|---|
| HOLDOUT-SAFE-17 | S/-/LOW | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM |
| HOLDOUT-PI-05 | F/PERSONAL_INFORMATION/MEDIUM | F/PERSONAL_INFORMATION/MEDIUM | F/PERSONAL_INFORMATION/MEDIUM | F/PERSONAL_INFORMATION/MEDIUM | F/PERSONAL_INFORMATION/MEDIUM | F/PERSONAL_INFORMATION/MEDIUM | F/PERSONAL_INFORMATION/MEDIUM |
| HOLDOUT-PROFANITY-14 | F/PROFANITY/HIGH | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM |
| HOLDOUT-SPAM-16 | F/SPAM/HIGH | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM |
| HOLDOUT-SPAM-19 | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW |
| HOLDOUT-SPAM-08 | F/SPAM/HIGH | F/SPAM/HIGH | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/HIGH | F/SPAM/HIGH | F/SPAM/MEDIUM |
| HOLDOUT-SAFE-09 | S/-/LOW | F/PERSONAL_INFORMATION/MEDIUM | F/PERSONAL_INFORMATION/MEDIUM | S/-/LOW | F/PERSONAL_INFORMATION/MEDIUM | S/-/LOW | S/-/LOW |
| HOLDOUT-PROFANITY-16 | F/PROFANITY/HIGH | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | S/-/LOW | S/-/LOW |
| HOLDOUT-PROFANITY-01 | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/HIGH | F/PROFANITY/HIGH | F/PROFANITY/MEDIUM |
| HOLDOUT-PROFANITY-06 | S/-/LOW | F/PROFANITY/MEDIUM | S/-/LOW | F/PROFANITY/MEDIUM | S/-/LOW | S/-/LOW | S/-/LOW |
| HOLDOUT-PROFANITY-13 | S/-/LOW | F/PROFANITY/LOW | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | S/-/LOW | S/-/LOW | F/PROFANITY/LOW |
| HOLDOUT-SAFE-01 | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW |
| HOLDOUT-PROFANITY-17 | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW |
| HOLDOUT-SPAM-15 | S/-/LOW | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM | F/SPAM/MEDIUM |
| HOLDOUT-PROFANITY-15 | F/PROFANITY/HIGH | F/PROFANITY/HIGH | F/PROFANITY/MEDIUM | F/PROFANITY/HIGH | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM | F/PROFANITY/MEDIUM |
| HOLDOUT-PROFANITY-08 | F/PROFANITY/HIGH | F/PROFANITY/MEDIUM | F/PROFANITY/HIGH | F/PROFANITY/HIGH | F/PROFANITY/HIGH | F/PROFANITY/HIGH | F/PROFANITY/HIGH |
| HOLDOUT-SPAM-09 | F/SPAM/HIGH | F/SPAM/HIGH | F/SPAM/HIGH | F/SPAM/HIGH | F/SPAM/HIGH | F/SPAM/HIGH | F/SPAM/HIGH |
| HOLDOUT-SAFE-10 | S/-/LOW | F/PERSONAL_INFORMATION/MEDIUM | S/-/LOW | F/PERSONAL_INFORMATION/MEDIUM | S/-/LOW | S/-/LOW | S/-/LOW |
| HOLDOUT-PROFANITY-05 | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW |
| HOLDOUT-PI-20 | S/-/LOW | F/PERSONAL_INFORMATION/MEDIUM | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW | S/-/LOW |

## Kafka/DB 및 비용 장부

- 각 조건20건, 총120건 DB 완료. 실제 afterCommit 관측, 최종 Lag0/DLT0, unsafe commit0, 저장 실패0.
- 모든 Case 관측 delivery1, Owner retryCount0, 관측된 추가 HTTP Retry0. 논리31과 HTTP31을 구분한다.
- 요청 ID 매핑/개별 Validation/DB 결과/Offset/SDK·HTTP Usage 기술 검증 모두 통과.
- 장애를 주입하지 않았으므로 실제 Provider 장애의 Retry/DLT/recovery-only 복구를 이번 Live에서 입증한 것은 아니다.
  해당 계약의 무과금 기존 검증과 구분한다.
- DB는 테스트 Context 종료 시 create-drop 및 Testcontainers 종료 대상이다. 최종20행은 종료 전 확인한 값이며,
  결과/Offset/시각을 별도 checkpoint와 JSON에 보존했다. 기존 로컬/Production DB는 사용하지 않았다.
- 기존12줄 journal prefix SHA `35924fde9b6de4437b5294b9e23daf48322c51daa012c509f08aa15c893e01d0` 유지.
  새 승인 BEGIN/31RESERVE/31SETTLE/END만 append됐다. 기존 Pilot/A1 END나 PASS를 추가하지 않았다.
- 기존 UNKNOWN **$0.01927680 보존**, 신규 UNKNOWN0, 진행 중 예약0.
- 기존 정산 $0.00060495 + 신규 보수적 정산 $0.00604125 + UNKNOWN $0.01927680
  = 전역 부담 **$0.02592300**. 단계 $0.10 / Benchmark $0.50 / 전역 $1 아래다.
- Gross 예약 이력 **$0.70087680**은 환급된 선예약도 누적한 값이지 실제 지출액이 아니다.
  장부 정산은 cache 할인을 미리 반환하지 않는 보수적 비용이며, 실제 Usage 할인 추정과 구분한다.
- 전체36 HTTP 이력은 과거5+신규31이다. Hash chain 검증 통과, journal76줄,
  이후 SHA `8aef3df31bbb897987d827f0736dd30619702348866f7fe4ab76e111828fd3ac`.
- 기존 보호 대상500개 중 journal만 정상 append됐고 나머지499개 해시 동일. private journal 자체는 Git에 포함하지 않는다.

## Grafana 및 검증 한계

기존 Dashboard/패널/디자인/datasource는 변경하지 않았다. 기존 Prometheus call count는 이번 실행 후에도19였다.
동결된 Live 실행기의 Registry는 기존 Prometheus에 노출되지 않고, `fakeMonitoring`은 Live에서 명시적으로 금지된다.
Manifest나 안전장치를 바꿔 연결을 강행하거나 Live 값을 Fake Counter로 재생해 실제 화면인 것처럼 캡처하지 않았다.
**이번 실제 Live 결과의 Dashboard 표시/조건별 캡처는 NOT_CAPTURED/NOT_CONNECTED**다.
기존 Fake 연결 검증은 유효하지만 이번 Live 표시의 증거로 대체하지 않는다.
기존 MySQL datasource도 Testcontainers DB를 조회하지 않으며 원래대로 유지했다.

Gradle `Issue68UsageBenchmarkLiveTest` 1건 기술적 측정 완료(PASS), 1m34s.
동일 Source hash의 직전 전체 Build1,485건/93skipped/failure0 결과는 재사용했고, 코드 변경이 없어 재실행하지 않았다.
초기 shell wrapper의 Ruby `filter_map` 호환 오류는 Gradle/Key 읽기/claim/journal 진입 전 발생했다.
장부/claim 불변과 HTTP0을 확인한 뒤 호환 문법으로 시작했으며 **Live 승인 실행 자체는1회**였다.
Key는 `.env`의 해당 항목 하나만 자식 환경에 전달했고 값·인증 header는 출력/기록하지 않았다.

## 결론 및 다음 Human 결정

이번 고정20건의 **비운영 실제 사용량·기술 정합성 비교는 완료**했다.
Token/비용 감소와 일부 E2E 개선은 관측했지만, Critical 하향과 새 FN 때문에 운영 품질 통과·채택은 선언하지 않는다.
가장 Token이 적은 Time50이나 cache 할인 비용이 낮은 Poll을 곧바로 최종 방식으로 선정하지 않는다.
품질 HOLD 및 Production Batch OFF를 유지하고, 나머지360건의 사용량 측정 필요성과 범위는 Human이 별도로 결정한다.
현재 승인 잔여분을 이유로 추가 호출·반복·Prompt 변경·유료 Pilot 재개를 자동 실행하지 않는다.
