# Issue #65 — Kafka Poll-Batch / Size-Time 비교 Evidence

## 검증 대상·범위

Production의 Poll-Batch는 그대로 두고 테스트 전용 단일 Consumer owner loop에서 여러 poll의 Rule miss를 수집했다.
이 문서의 비교는 Native / 현재 Poll-Batch / Size-Time10(wait0·20·50·100ms)의 로컬 무과금 실험이다.
운영 도입·실제 OpenAI Token/비용·품질 개선을 입증하지 않는다. **운영 기본 Batch OFF 유지**.

## 계약·기준 코드

- [Issue #65](https://github.com/gpekd5/bobfull-backend/issues/65), 상위 #55 OPEN.
- [Production 최소 경계 분리 확인 요청](https://github.com/gpekd5/bobfull-backend/issues/65#issuecomment-6307083104), [Human 승인 대조·단계별 계약](https://github.com/gpekd5/bobfull-backend/issues/65#issuecomment-6307254554).
- 실행 HEAD: `5a4da85e0f04c8df4286185e71b4cca67c128df6` (PR #66 Merge), 실행 당시 #65 소스는 미커밋.
- 측정 완료: `2026-10-21T03:37:48.824020Z` (2026-10-21 12:37:48 KST).
- 최종 보존 Commit/CI/Review는 PR/Issue에 연결한다. 자기 자신의 SHA 삽입을 위한 문서 재작성 없음.
- [측정 raw](size-time-results.json)의6개 소스 SHA-256으로 실행 소스와 보존 Commit을 대조한다.
- 기존 [#62](../62-kafka-poll-batch-measurement/README.md)는 역사적 Baseline. 당시 수치를 이번 개선율 계산의 Control로 사용하지 않는다.
- #55/#62/#64 Evidence/raw/해시는 수정하지 않았다.

### 측정 계약

- Primary: 실제 요청 크기 분포/충전율과 Kafka 최초 수신→개별 DB commit p50/p95.
- Secondary: Native/Batch 논리 요청, ACK→commit, Buffer wait/Flush 원인/최대 참조, Provider 시간, Lag/해소 및 반복 편차.
- 안전 확인: 미완료 offset 조기 commit0, DB 완료/최종 Lag0, Retry/DLT·중복·lifecycle, Consumer API owner thread 한정.
- size10/maxWait0·20·50·100ms/Buffer20은 실험 변수이지 운영 SLO/권장값이 아니다.

## Production 동작 보존 경계 분리

변경 Production 파일은 승인된 **2개**다.

| 대상 | 변경 | 불변 |
|---|---|---|
| ChatModerationBatchService | prepare→PreparedItem과 실행 분리. 기존 analyze는 즉시 같은 순서로 위임 | 완료 Skip/Rule/매핑/Validation/개별 저장/예외/Metric |
| ChatModerationBatchRetryProcessor | 실행 callback overload만 추가, 기존 경로는 service::analyze | #64 그룹 예산/indexed Retry 연결/recovery 상태 머신/생성자 |

- 수집 뒤의 analyzePrepared는 요청 직전 완료 상태를 재확인한다. 그 사이 다른 Consumer가 완료한 항목은 요청하지 않는다.
- Rule/본문 조회를 다시 수행하지 않는다. 준비가 실패한 항목의 다음 시도만 다시 준비한다.
- 새 수집/재시도 대기는 기존 분석 latency_millis에 추가하지 않는다. 기존 준비 시간은 보존하고 준비 완료 이후의 추가 대기를 제외한다.
  실제 수신→DB commit과 별도 Buffer wait에는 대기가 포함된다. 기존 생성→저장 e2e Timer를 수신→commit으로 바꾸지 않았다.
- Production Native/Poll Consumer·Listener·ACK/ErrorHandler·기본 Retry 설정·Batch OFF/Prompt/Policy/Rule/Model·API/Schema/transaction은 변경하지 않았다.
- 기존 analyze는 deferred 재확인/시간 재기준화 경로를 사용하지 않는다.

## 테스트 전용 Owner Loop

- Consumer 생성·subscribe·poll·pause/resume·commitSync·close 및 callback은 단일 owner thread에서 실행한다.
  위임 Consumer의 모든 API 호출 thread를 검사한다. 별도 Provider Worker/Timer 없음.
- Rule 처리 후 LLM 대상10 또는 최초 pending 항목 고정 deadline에 Flush. 부족한1건은 Native.
- 다음 최대10건 poll을 수용할 공간이 없으면 capacity Flush. Buffer에는 대기·처리·commit을 기다리는 완료 record 참조도 포함한다.
- 외부 Kafka fetch cache/전체 JVM heap을20건으로 제한한다는 의미는 아니다.
- partition별 완료 prefix만 명시적 commitSync한다. DB commit과 Kafka committed offset은 별개다.
- 요청 실패 그룹은 기존 Processor로 처리한다. 항목 실패는 **frozen work의 해당 원본 record**에 기존 DefaultErrorHandler.handleOne의 tracker/분류/Recoverer만 적용한다.
  누적된 예전 index를 현재 poll의 handleBatch에 전달하지 않으며 fake Container/전체 poll DLT fallback을 쓰지 않는다.
- Recovery-only/RECOVERED는 #64 동일 구현을 사용한다. Owner는 실패 작업을 유지하며 신규 record를 실패 그룹에 합치지 않는다.
- Retry 동안 pause + poll로 membership을 유지한다. 항목 tracker의 기존 backoff 동작은 재사용한다.
- revoke는 완료 prefix만 commit 시도 후 해당 partition 상태를 제거한다. lost는 commit 없이 제거한다.
  shutdown은 새 Provider flush를 시작하지 않고 미완료 항목을 Kafka 재전달에 맡긴다.
- 기존 Spring indexed handler와 commit 시점까지 동일한 구현은 아니다. Owner는 실패 작업 완료까지 보수적으로 commit을 미룰 수 있다.
  이 차이와 재균형/메모리 소실 후 중복 가능성을 숨기지 않는다.

## 안전성 검증

1단계 기존 Native/Poll 및 경계 분리 회귀100건 PASS 뒤 Owner Loop를 구현했다.
측정 직전 Owner Kafka/H2 안전성 **14 tests / skipped0 / failures0 / errors0**, BUILD SUCCESSFUL in18s.

| 경계 | 실제 검증 |
|---|---|
| 여러 poll·Rule·완료 Skip | 원본 record 유지, Rule/이미 완료 제외, 최종 DB/offset |
| 공유 Provider 실패 | 5건/max2에서 Batch2/Native0, DLT5/FAILED5 |
| 항목 Validation | 정상 결과 보존, Batch1 이후 실패1건 Native1 |
| 부분 DB 저장 실패 | 실제 Repository transaction 내부 advice로 첫 저장 실패, 정상 형제 보존/실패 항목 재처리 |
| DLT publish 실패 | 실패 중 source offset0/해당 DB 미기록, Provider2 고정, send3시도/DLT2 |
| DLT 후 최종 DB 실패 | Provider2 고정, DLT3, 최종 FAILED |
| indexed 소진 후 DB 실패 | Batch1/Native1 고정, DLT2/recovery-only |
| offset commit 실패 | DB 성공은 유지, shutdown/restart 후 완료 Skip·추가 Provider0 |
| Buffer·deadline | 최대20참조, capacity Flush, 후속 Rule완료가 앞 미완료offset을 넘지 않음, 추가 유입으로 deadline 연장하지 않음 |
| shutdown | 대기 항목 Provider0, commit0, 다음 owner 재전달 처리 |
| 실제 rebalance | Consumer.enforceRebalance로 실제 revoke, 새 Provider 강제 flush0/미완료 replay |
| Lost 통지 | owner thread에서 callback **통지 주입**, commit0/버퍼 제거/재전달. 실제 session timeout/fencing 검증 아님 |
| 복수 partition | 실제2partition 원본 record, 항목별 요청 참여2, partition별 DLT2/DB/최종offset2 |
| thread 소유권 | 위임 Consumer API 호출의 owner thread 위반0 |

실제 JVM 강제 kill은 미수행이다. shutdown/rebalance를 강제 kill 보장으로 확대하지 않는다.
이 실험에서 DB는 Consumer thread와 별도 ApplicationContext에 있어 stop/start 동안 유지됐다.
추후 실제 별도 JVM kill을 검증한다면 DB가 함께 사라지지 않는 별도 수명 환경이 필요하다.
안전성 fixture는 실제 Service/JPA에 Port Test Double을 주입하며, 아래 정상 부하 측정은 실제 Fake Adapter Bean을 사용한다.

## 공통 환경·실행 조건

- Kafka Testcontainers cp-kafka7.7.1, 조건별 격리 H2 MODE=MySQL/create-drop, 실제 JPA/감사 설정.
- 같은 JVM의6개 실제 Context, 측정은1조건씩 순차 실행. 다른 조건의 Consumer는 idle. CPU/메모리 격리 시험 아님.
- 실제 Fake Native/Batch Adapter 요청당60ms 설정. Native N회 대기로 Batch를 흉내 내지 않는다.
- source1partition/concurrency1/max.poll.records10/auto-commit=false.
- fetch.min.bytes1/fetch.max.wait100ms/max.poll.interval300000ms/earliest.
- Producer acks=all/idempotence=true/linger0, 같은 합성 입력과 목표 발행 스케줄.
- 매5번째 단일 Rule hit, 나머지 동일 Rule miss. 실제 사용자 본문/PII 수집 없음.
- 조건별10건 Rule miss warm-up(총60건)은 측정에서 제외.
- 패턴별3반복. 조건 순서는 시나리오/반복에 따른 고정 회전·역전으로 교차.
- Native ACK RECORD, Poll ACK BATCH. Owner는 명시적 안전 prefix commitSync이며 poll timeout은 min(100ms, 남은 deadline).
  따라서 같은 Consumer 기본 설정이더라도 poll 간격/commit 구조가 완전히 같지는 않다.
- Production 기본 max-attempts3/backoff1000ms를 유지한 Context. 안전성 장애 fixture는 비교를 위해 max2/backoff10ms를 별도 사용했다.
- 이번 부하 측정은 정상 경로다. 장애 시 처리량/지연 비교로 해석하지 않는다.

| 패턴 | 회당 메시지 | 목표 간격 | Rule / LLM |
|---|---:|---:|---:|
| low | 8 | 250ms | 1 / 7 |
| interval | 24 | 40ms | 4 / 20 |
| burst | 40 | 0 | 8 / 32 |
| sustained | 60 | 5ms | 12 / 48 |

sustained는 약0.295초의 짧은 연속 주입이며 장시간 정상상태 측정이 아니다.
총72실행/2,376측정 메시지, 조건별396건(Rule75/LLM321). warm-up은 별도다.

## 시간 관측·원본

- 최초 수신: ConsumerInterceptor.onConsume, 같은 poll의 모든 record에 같은 nanoTime.
- DB commit: 실제 Repository transaction 안 TransactionSynchronization.afterCommit.
- flush 반환/Repository 반환을 각각 보존하여 수신≤flush≤commit≤Repository반환을 검사한다.
- ACK→commit은 Kafka 미poll 대기를 포함하지만 Chat SEND/Outbox 전체 E2E가 아니다.
- Buffer wait는 Rule miss 준비 후 입장→Flush 선택까지다. Provider 실행 중 뒤 pending 항목은 deadline을 넘을 수 있다.
  maxWait는 실시간 완료 상한이 아니며 deadline 초과 표본도 raw에 그대로 보존한다.
- p50/p95는3회 원본 표본 pooled nearest-rank. run별p95를 평균하지 않는다.
- raw에는 원문/DB id/eventId/memberId/chatRoomId/인증정보가 없다. ordinal은 합성 입력 순번이다.
- 원본 build JSON SHA-256: `acdcf55e27595095b5881153a660c4c8a0dd8d101e7383e76b32ab324d18f060`.
- 저장본은 pretty formatting만 변경했으며 JSON 파싱 값 동일성을 검사했다. 저장본 SHA-256:
  `109fd54a5f14fe11f392e80dd572759e1b1cc6f2c51bcb838abe8e28539f5506`.

## 실측 — 논리 요청 수

각 셀은 **Native 요청+Batch 요청**(3회 합계). Token/HTTP attempt/실제 비용과 다른 분모다.

| 패턴 | Native | Poll | Time0 | Time20 | Time50 | Time100 |
|---|---:|---:|---:|---:|---:|---:|
| low | 21+0 | 21+0 | 21+0 | 21+0 | 21+0 | 21+0 |
| interval | 60+0 | 32+14 | 32+14 | 9+24 | 2+24 | 0+18 |
| burst | 96+0 | 4+13 | 3+13 | 0+12 | 0+13 | 0+12 |
| sustained | 144+0 | 6+21 | 7+20 | 2+21 | 4+16 | 0+15 |

조건 전체 논리 요청 합계는 Native321, Poll111, Time0 110, Time20 89, Time50 80, Time100 66이다.
이 합계를 실제 OpenAI 호출/Token 절감률로 환산하지 않는다.

## 실측 — E2E 및 대기

단위ms. wait는 LLM 대상만이며 Native/Poll에는 별도 수집 대기가 없어 N/A로 표시한다.

| 패턴 | 조건 | 수신→commit p50 / p95 | ACK→commit p95 | Buffer wait p95 | 각 run의 수신→commit p95 |
|---|---|---:|---:|---:|---|
| low | native | 70.400 / 74.228 | 75.761 | N/A | 76.892 / 72.874 / 72.256 |
| low | poll | 70.630 / 74.225 | 75.224 | N/A | 75.212 / 74.225 / 74.212 |
| low | time0 | 72.663 / 75.468 | 76.629 | 0.064 | 76.689 / 75.336 / 75.143 |
| low | time20 | 93.290 / 96.558 | 97.905 | 21.239 | 97.060 / 95.831 / 94.865 |
| low | time50 | 120.191 / 126.926 | 128.171 | 51.209 | 126.926 / 127.150 / 124.456 |
| low | time100 | 170.590 / 177.210 | 178.422 | 101.254 | 177.282 / 175.838 / 174.454 |
| interval | native | 71.621 / 271.117 | 474.111 | N/A | 286.312 / 207.242 / 271.117 |
| interval | poll | 68.692 / 73.460 | 200.275 | N/A | 73.460 / 72.577 / 72.773 |
| interval | time0 | 68.193 / 73.599 | 201.083 | 1.094 | 74.114 / 72.577 / 73.155 |
| interval | time20 | 88.535 / 94.647 | 160.000 | 21.792 | 94.647 / 96.085 / 93.229 |
| interval | time50 | 116.063 / 123.904 | 188.568 | 51.376 | 125.385 / 122.730 / 123.428 |
| interval | time100 | 144.887 / 174.134 | 236.107 | 101.835 | 168.794 / 174.763 / 173.907 |
| burst | native | 273.625 / 561.754 | 2213.959 | N/A | 551.678 / 561.754 / 551.692 |
| burst | poll | 67.452 / 74.631 | 422.430 | N/A | 75.263 / 72.924 / 71.713 |
| burst | time0 | 71.465 / 76.919 | 385.853 | 2.784 | 77.673 / 75.619 / 73.162 |
| burst | time20 | 71.138 / 140.143 | 320.337 | 71.538 | 140.000 / 145.490 / 71.993 |
| burst | time50 | 71.822 / 143.723 | 298.943 | 73.626 | 144.758 / 75.925 / 133.186 |
| burst | time100 | 68.954 / 166.452 | 304.382 | 101.173 | 166.136 / 173.880 / 137.930 |
| sustained | native | 272.912 / 557.473 | 2963.929 | N/A | 566.967 / 554.922 / 554.180 |
| sustained | poll | 66.371 / 72.755 | 351.623 | N/A | 72.627 / 73.926 / 71.522 |
| sustained | time0 | 67.790 / 73.845 | 352.539 | 1.457 | 77.231 / 71.375 / 71.796 |
| sustained | time20 | 67.134 / 142.686 | 262.286 | 74.222 | 136.207 / 144.949 / 131.163 |
| sustained | time50 | 67.727 / 125.047 | 296.552 | 51.183 | 100.242 / 125.959 / 111.187 |
| sustained | time100 | 68.229 / 109.357 | 152.862 | 45.154 | 108.660 / 102.977 / 109.983 |

## 실측 — Batch 크기·Flush 원인

분포는 크기:요청횟수. 평균/충전율은 Batch 요청만을 분모로 하며 Native1건은 제외한다.
low는 요청이 없어 평균/충전율 N/A다. Buffer 상한20은 **참조 수**이며 전체 메모리 수치가 아니다.

| 패턴 | 조건 | Batch 크기 분포 | 평균 / 충전율 | 최대Buffer | Flush 원인:횟수 |
|---|---|---|---:|---:|---|
| low | poll | 없음 | N/A / N/A | N/A | N/A |
| low | time0 | 없음 | N/A / N/A | 1 | deadline:21 |
| low | time20 | 없음 | N/A / N/A | 1 | deadline:21 |
| low | time50 | 없음 | N/A / N/A | 1 | deadline:21 |
| low | time100 | 없음 | N/A / N/A | 1 | deadline:21 |
| interval | poll | 2:14 | 2.000 / 20.00% | N/A | N/A |
| interval | time0 | 2:14 | 2.000 / 20.00% | 2 | deadline:46 |
| interval | time20 | 2:21, 3:3 | 2.125 / 21.25% | 3 | deadline:33 |
| interval | time50 | 2:14, 3:10 | 2.417 / 24.17% | 3 | deadline:26 |
| interval | time100 | 3:12, 4:6 | 3.333 / 33.33% | 5 | deadline:18 |
| burst | poll | 3:1, 4:1, 6:1, 7:1, 8:9 | 7.077 / 70.77% | N/A | N/A |
| burst | time0 | 2:1, 5:1, 7:2, 8:9 | 7.154 / 71.54% | 10 | deadline:16 |
| burst | time20 | 2:1, 6:3, 7:1, 9:1, 10:6 | 8.000 / 80.00% | 20 | size:6, deadline:5, capacity:1 |
| burst | time50 | 2:2, 3:1, 4:1, 6:1, 9:1, 10:7 | 7.385 / 73.85% | 20 | size:7, deadline:5, capacity:1 |
| burst | time100 | 2:1, 3:2, 9:2, 10:7 | 8.000 / 80.00% | 20 | size:7, capacity:2, deadline:3 |
| sustained | poll | 2:3, 5:1, 6:4, 7:1, 8:12 | 6.571 / 65.71% | N/A | N/A |
| sustained | time0 | 2:2, 5:2, 6:2, 7:1, 8:13 | 6.850 / 68.50% | 10 | deadline:27 |
| sustained | time20 | 2:4, 4:3, 5:1, 6:3, 9:1, 10:9 | 6.762 / 67.62% | 20 | deadline:13, size:9, capacity:1 |
| sustained | time50 | 2:1, 8:4, 9:4, 10:7 | 8.750 / 87.50% | 14 | deadline:9, capacity:4, size:7 |
| sustained | time100 | 9:6, 10:9 | 9.600 / 96.00% | 15 | capacity:6, size:9 |

## Provider 시간·Lag·정합성

- Fake 설정은60ms이나 실제 Port 관측 시간은 조건별 평균 약65.27~67.24ms, p95 약69.42~70.09ms였다.
  스케줄링/호출 부가시간을 포함하며 원인을 세부 분해한 측정은 아니다. 실제 OpenAI latency로 해석하지 않는다.
- 72실행 모두 DB 완료, 최종 Lag0, Listener/저장 실패·미완료·transaction 관측 위반0.
- Lag는 committed를 먼저/end를 나중에 조회하는 두 Admin RPC의 약100ms 표본이다. 원자적 snapshot/절대 peak 아님.
- raw.lag에 offset/상대시각 표본과 summary.drainAfterLastPublishAckMs(마지막DBcommit까지)를 보존했다.
  이 필드를 Lag0 최초 관측 시각과 혼동하지 않는다.

| 패턴 | 조건 | 관측 peak Lag run1 / 2 / 3 |
|---|---|---|
| low | native | 1 / 1 / 1 |
| low | poll | 1 / 1 / 1 |
| low | time0 | 1 / 1 / 1 |
| low | time20 | 1 / 1 / 1 |
| low | time50 | 1 / 1 / 1 |
| low | time100 | 1 / 1 / 1 |
| interval | native | 8 / 8 / 8 |
| interval | poll | 5 / 5 / 5 |
| interval | time0 | 5 / 5 / 5 |
| interval | time20 | 4 / 3 / 4 |
| interval | time50 | 5 / 5 / 5 |
| interval | time100 | 6 / 6 / 6 |
| burst | native | 39 / 39 / 39 |
| burst | poll | 39 / 39 / 39 |
| burst | time0 | 39 / 39 / 38 |
| burst | time20 | 28 / 29 / 28 |
| burst | time50 | 28 / 28 / 28 |
| burst | time100 | 28 / 29 / 28 |
| sustained | native | 55 / 55 / 55 |
| sustained | poll | 46 / 46 / 45 |
| sustained | time0 | 45 / 45 / 47 |
| sustained | time20 | 40 / 40 / 30 |
| sustained | time50 | 27 / 27 / 27 |
| sustained | time100 | 25 / 25 / 25 |

## 결과 해석·권고

- 저유입은 모든 방식21논리 요청으로 동일하다. Poll p95 74.225ms 대비 Time100 177.210ms로 대기만 증가했다.
- 일정 간격은 Poll46요청/p95 73.460ms, Time20 33요청/94.647ms, Time100 18요청/174.134ms다.
  Time20의 ACK→commit p95는200.275→160.000ms였지만, 수신 이후 지연은 증가했다. 지표 하나로 결론내지 않는다.
- Burst는 Poll17요청/p95 74.631ms/ACKp95 422.430ms, Time20 12요청/140.143ms/320.337ms였다.
  더 적은 요청·짧은 Kafka 포함 대기와 더 긴 수신 후 처리 대기가 동시에 관측됐다.
- 짧은 연속은 Poll27요청/p95 72.755ms/ACKp95 351.623ms, Time100 15요청/109.357ms/152.862ms였다.
  Time100의15회는 size9/capacity6이며 deadline Flush0이다. 이를100ms 기다린 단일 효과라고 단정하지 않는다.
- Time20에서도 뒤 pending의 wait p95가 약71~74ms인 패턴이 있다. 앞 Provider 처리/같은 poll 대기 및 deadline 검사 구조를 함께 봐야 한다.
- Time0는 Poll과 거의 같은 요청 수(110/111)지만 구조/추가 완료 조회/스케줄링 차이가 있으므로 완전 등가라고 하지 않는다.
- **현재 Production Poll-Batch/기본 OFF 유지 권고.** Size/Time은 일부 패턴에서 논리 요청을 더 줄였지만,
  저유입 이득이 없고 수신→commit 지연·수명주기/offset 관리 복잡성이 추가됐다.
  허용 추가 지연/SLO·실제 트래픽·품질/Provider 조건이 확정되기 전 일괄 도입하지 않는다.
- 요청이 적다는 사실을 실제 비용/Token/품질 개선으로 일반화하지 않는다. 실험은 운영 활성화 승인이 아니다.

## 실행·검증

```bash
# 1단계 동작 보존 회귀 (실행 당시100건 PASS)
env -u OPENAI_API_KEY ./gradlew :test \
  --tests '*ChatModerationBatch*' --tests '*ChatModerationServiceTest' \
  --tests '*ModerationRulePolicyTest' --tests '*ChatModerationProviderContextTest' \
  --tests '*SpringAiModerationBatchAdapterTest' \
  --tests '*ChatModerationFinalFailurePersistenceIntegrationTest' \
  --tests '*ChatModerationDltPublishFailureIntegrationTest' --console=plain --no-daemon

# Owner 안전성14건 PASS 후에만 전용 측정 실행
env -u OPENAI_API_KEY ./gradlew :test --tests '*SizeTimeBatchSafetyIntegrationTest' --console=plain --no-daemon
env -u OPENAI_API_KEY ./gradlew :test --tests '*PollBatchMeasurementsTest' \
  --tests '*ChatModerationBatchServiceTest' kafkaEvidenceTest \
  --tests '*SizeTimeBatchComparisonMeasurementTest' --console=plain --no-daemon
```

- 1단계 회귀100건 PASS in1m3s. 이후 수집 대기 제외 latency 계약 테스트1건을 추가했다.
- Owner14건 PASS in18s. 최초 테스트 람다 checked exception compile 실패와 Repository AOP proxy Spy 설정1건 실패는
  테스트 구현 문제로 수정했다. 실제 transaction 내부 advice로 장애를 주입했으며 첫 실패를 PASS로 바꾸지 않는다.
- 전용 측정 BUILD SUCCESSFUL in1m52s, 72실행/2,376건. 측정과 전용 단위/Service 테스트가 통과했다.
- 최종 단독 강제 회귀/build:
  `env -u OPENAI_API_KEY ./gradlew test build --rerun-tasks --console=plain --no-daemon`.
  **BUILD SUCCESSFUL in3m33s**, root **1,183 tests / skipped86 / failures0 / errors0**,
  Lambda **11 tests / skipped0 / failures0 / errors0**. 합계 **1,194 tests / skipped86 / failures0 / errors0**.
  이번 최종 실행은 root/Lambda 모두 재실행했다(12 tasks executed). 전용 부하 측정은 기본 test 합계에 포함하지 않는다.
  그 중 관련 suite **119 tests / skipped0 / failures0 / errors0**를 JUnit XML로 별도 집계했다.
- 관련 회귀와 전체 build를 겹쳐 시작한 중간 실행에서 결과 파일 EOFException이 발생했다.
  겹친 전체 실행도 in-progress-results 파일 NoSuchFileException으로 실패했다. 두 실행을 PASS로 쓰지 않았고,
  모두 종료한 뒤 기존 build 원본을 삭제하지 않는 --rerun-tasks 단독 명령으로 위 결과를 재확인했다.
- git diff --check PASS, raw source6개 hash 일치, JSON 값 동일, 기존 Evidence/설정/추가 Production 클래스 변경 없음.
  최종 보존 Commit/CI/최신 Head AI Review는 PR 및 Issue 댓글에 연결한다.

## 한계·후속

- 3반복/소규모 로컬/H2/고정 Fake60ms/1partition·concurrency1. 통계적 유의성/운영 용량/품질·비용 보장 없음.
- 실제 Provider 응답시간이 Batch 크기와 무관하다는 증거 아님. Token/Estimated Cost는 측정하지 않았다.
- 정상 경로 부하 비교와 장애 계약 테스트를 분리했다. 장기 지속 유입·CPU/heap 부하 비교는 미수행.
- 실제 revoke와 Lost 통지 주입을 구분했다. 실제 JVM kill/session timeout/fencing 전체 행렬은 미수행.
- Owner 실험 입력은 유효 v1 합성 이벤트다. 잘못된 이벤트는 미commit fail-closed 종료하며 Production invalid-event DLT 동작의 대체 구현이라고 주장하지 않는다.
- 메모리 소실 후 Provider/DLT 중복 가능. durable 호출 상한/exactly-once 없음.
- 테스트 전용 구조/관측은 운영 코드와 다르다. 원인 증명을 Grafana/충전율 하나로 대체하지 않는다.
- 후속은 허용 지연/대표 유입·실제 MySQL·별도 수명 DB를 사용한 process kill·실제 AI 품질/비용 검증이며 별도 승인 필요.
- Draft PR만 제출하며 Ready/Merge/운영 활성화는 Human 책임이다.
