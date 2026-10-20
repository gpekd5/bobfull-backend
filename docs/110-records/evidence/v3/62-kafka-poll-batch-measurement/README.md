# Issue #62 — Kafka Poll-Batch 충전율 및 E2E 지연 측정

## 검증 대상과 판정

기존 Production Native(Batch OFF)와 Poll-Batch ON을 **로컬 Testcontainers Kafka + H2/JPA + 실제 Fake Adapter**로 비교했다.
Production 처리/계측/설정은 변경하지 않았다. 이 문서의 Before/After는 같은 코드의 OFF/ON 비교이며,
이번 Issue에서 새 최적화를 구현한 결과가 아니다. **PASS: 명시한 로컬 측정·정합성 범위**.
실제 OpenAI 성능·Token/비용 절감·모델 품질·운영 활성화 승인을 의미하지 않는다.

## 측정 계약

- Primary: 유입 패턴별 poll 및 Rule 이후 Batch 크기/충전율, Kafka 수신→개별 DB commit p50/p95.
- Secondary: Native/Batch 논리 요청·처리 메시지 수, 발행 ACK→commit 지연, Lag 표본·해소, 반복 편차.
- 안전 확인: 실패/미완료/Transaction 관측 위반0, 최종 completed row 수 일치, 기존 Native·Retry/DLT 회귀, 기본 OFF.
- 측정 전 확정한 계약: [Issue 댓글](https://github.com/gpekd5/bobfull-backend/issues/62#issuecomment-6293664272).

## 기준 코드와 원본 추적

- Before/After production 기준 및 실제 실행 HEAD: `027584a6b556b4b947449470a60a9445e7f39de4` (PR #61 Merge).
- 실행 시 테스트 전용3파일은 **미커밋** 상태였다. 결과의 `head`를 최종 PR Commit에서 실행한 것처럼 바꾸지 않는다.
- 원본 [poll-batch-results.json](poll-batch-results.json)에 실행 UTC 시각, HEAD, 측정 소스3파일의 SHA-256이 있다.
  해당 해시와 Commit되는 소스가 같은지 별도 검사한다. 최종 Head/CI/Review는 PR 본문에 기록한다.
- 측정 완료 시각: `2026-10-20T14:54:12.580114Z` = 2026-10-20 23:54:12 KST.
- 원본은 `build/issue62/poll-batch-results.json`에서 복사했다. 저장본에 마지막 개행만 추가되었으며 JSON 파싱 후 값의 동일성을 확인했다.
  원본 SHA-256: `631919ab30080852b1c69883748df64573ac5c2c36cfac5faf524e6db42f4fe1`.
  저장본 SHA-256: `09d392cbd987353debf99084a6fc0ba4212a7edbabb1f30a371cc18bd4c52e7d`.
- 과거 #55 실험/Evidence/raw JSON/해시는 변경하지 않았다.

## 환경·입력·실행 조건

| 항목 | 양쪽 공통 조건 또는 의도한 차이 |
|---|---|
| Broker | Testcontainers `confluentinc/cp-kafka:7.7.1`, 같은 로컬 Broker의 모드별 source/DLT Topic |
| DB | 모드별 격리 H2 `MODE=MySQL`, create-drop, 같은 Repository/Entity/JPA Auditing |
| 실행 | 같은 테스트 JVM에서 두 실제 Context, 측정은 한 모드씩 순차 실행. CPU/메모리 격리 성능 실험은 아님 |
| Consumer | source1 partition, concurrency1, `max.poll.records=10`, `enable.auto.commit=false` |
| fetch/poll | `fetch.min.bytes=1`, `fetch.max.wait.ms=100`, poll-timeout100ms, `max.poll.interval.ms=300000`, earliest |
| ACK | Native RECORD / Poll-Batch BATCH — 기존 구현 차이를 보존 |
| Producer | 같은 코드/순서/목표 주입 스케줄, acks=all, idempotence=true, linger.ms=0 |
| Provider | 기존 Fake Native/Batch Adapter, SAFE, **요청당60ms**. Batch를 단건 N회 호출로 대체하지 않음 |
| 입력 | 같은 합성 문장 순서. 매5번째만 기존 단일 Rule hit, 나머지 Rule miss. 실제 사용자 원문/PII 없음 |
| warm-up | 모드별10건(모두 Rule miss), partition 할당 및 완료/Lag0 확인. 측정 표본에서 제외 |
| 반복 | 각 시나리오3회. 반복1·3 Native→Batch, 반복2 Batch→Native |

Kafka 핵심 Consumer 설정은 interceptor에서 실제 `ConsumerConfig`를 읽고 검사했다.
poll-timeout/concurrency/ACK는 테스트의 Factory/Listener 설정이다. 각 Context의 Listener1개와 assigned partition1개도 확인한다.
두 모드는 같은 수의 이전 입력을 처리한 상태에서 각 쌍을 비교한다. 시나리오마다 DB를 비우는 방식은 아니다.

| 시나리오 | 회당 메시지 | 목표 간격 | Rule / LLM 대상(회당) | 모드별3회 표본 |
|---|---:|---:|---:|---:|
| low 저유입 | 8 | 250ms | 1 / 7 | 24 |
| interval 일정 간격 | 24 | 40ms | 4 / 20 | 72 |
| burst | 40 | 대기 없음 | 8 / 32 | 120 |
| sustained 짧은 연속 유입 | 60 | 5ms | 12 / 48 | 180 |

`sustained`는 약0.295초에60건을 주입하는 **짧은 고율 연속 유입**이다. 장시간 지속 부하·정상상태/용량 검증이 아니다.
간격은 목표 주입 스케줄이고 실제 Broker 도착 간격이 정확히 같다고 주장하지 않는다. poll 분포의 반복 차이는 그대로 보존했다.
총24실행/792메시지이며, 별도의 warm-up20건은 표에서 제외했다.

## 시간과 Commit의 정의

| 시각 | 관측 위치/의미 |
|---|---|
| 수신 | `ConsumerInterceptor.onConsume`, 역직렬화 이후 poll 반환 직전. **같은 poll의 모든 레코드에 같은 시각** |
| flush 반환 | 실제 Repository TransactionInterceptor 안쪽 advice의 `saveAndFlush` 진행 반환 직후. 아직 transaction commit 전 |
| DB commit 완료 | 실제 Repository transaction의 `TransactionSynchronization.afterCommit` callback 진입 |
| Repository 반환 | TransactionInterceptor 바깥 advice에서 호출 반환 직후. commit 후 |
| Offset/Lag | DB commit과 별개인 Kafka group committed offset을 AdminClient로 조회 |

테스트는 외부 Service transaction이 없고 Repository 안에는 실제 transaction/synchronization이 있음을 확인한다.
모든 표본에 `수신 ≤ flush 반환 ≤ afterCommit ≤ Repository 반환`을 검사하고,
별도 Repository 조회로 completed row를 확인했다. flush/메서드 반환을 DB commit 시각으로 대신 쓰지 않았다.
이는 H2 transaction commit 확인이며 실제 MySQL의 디스크 내구성·복제 보장이 아니다.

수신→commit은 Broker에서 **아직 poll되지 않은 대기 시간은 포함하지 않는다**.
Native Listener 개별 진입을 시작점으로 삼아 같은 poll의 순차 처리 대기를 숨기지 않았다.
보조 발행 ACK→commit은 Kafka 대기를 포함하지만 ACK callback 시각 기준이므로 전체 Outbox/Chat SEND E2E는 아니다.
`createdToCommitMs`는 별도 wall-clock 값이며 수신→commit은 같은 JVM의 monotonic nanoTime 차이다.

## 기존 Metric 재사용 범위

| 기존 Metric | 이번 사용/제한 |
|---|---|
| `bobfull.ai.moderation.poll.size` | Batch Listener non-empty poll 수를 테스트 interceptor와 delta 교차 확인. Native에서는 미생성 |
| `batch.size` | Batch 요청 수를 실제 Fake Batch 호출 수와 delta 교차 확인 |
| `batch.fill.ratio` | 기존 의미는 요청 크기/10. 원본 요청 크기로 분포/가중 평균을 계산 |
| `processing{path}` | Batch의 rule/native/batch 처리 메시지 합계를 delta 교차 확인. 요청 수와 구분 |
| `e2e{path}` | 기존 **생성→저장 처리 후** Timer. 수신→DB commit으로 이름만 바꾸지 않음 |
| `batch.failure{stage}`, 기존 Retry/DLT | 이번 정상 부하 측정에서 인위적으로 장애를 추가하지 않음. 기존 장애 suite를 관련 회귀로 실행 |

Native OFF에는 Batch metric이 없으므로 raw의 해당 값은 `null`(미제공)이다. Counter 미생성을 실제 관측0처럼 쓰지 않았다.
공통 poll 수신/DB commit/실제 요청 관측은 테스트 전용 interceptor/advice로 확보했다.
Production에 새 Metric/tag를 추가하지 않았고, raw에는 DB id/eventId/memberId/chatRoomId/본문/인증정보가 없다.
`ordinal`은 합성 입력의 회당 순번이다.

## Before(Native) / After(Poll-Batch) 실측

다음 p50/p95는 **3회 원본 메시지 표본을 합쳐 nearest-rank로 다시 계산**했다. 각 run의 percentile 평균이 아니다.
Rule 처리 메시지도 포함한다. 단위 ms.

| 시나리오 | 모드별 N | Native p50 / p95 | Batch ON p50 / p95 | Native 논리 요청 | Batch ON Native / Batch 논리 요청 |
|---|---:|---:|---:|---:|---:|
| 저유입 | 24 | 67.657 / 70.647 | 67.154 / 72.796 | 21 | 21 / 0 |
| 일정 간격 | 72 | 68.176 / 272.931 | 66.741 / 71.118 | 60 | 34 / 13 |
| Burst | 120 | 267.661 / 548.752 | 70.640 / 80.283 | 96 | 1 / 17 |
| 짧은 연속 유입 | 180 | 266.595 / 535.866 | 67.857 / 72.286 | 144 | 6 / 21 |

### Rule 이후 실제 Batch 분포

표기 `크기:요청횟수`. 평균/충전율은 실제 Batch 요청만을 분모로 한다. Native underfill1건은 Batch에 포함하지 않는다.
Batch 요청0인 저유입의 평균/충전율은 0%가 아니라 **N/A**다.

| 시나리오 | Batch 크기 분포(3회 합계) | 평균 크기 / 충전율 | run별 평균 크기 | Batch 처리 메시지 / LLM 대상 | Batch 요청 / 전체 Provider 요청 |
|---|---|---:|---|---:|---:|
| 저유입 | 없음 | N/A | N/A | 0 / 21 | 0 / 21 |
| 일정 간격 | 2:13 | 2.000 / 20.00% | 2.000 / 2.000 / 2.000 | 26 / 60 | 13 / 47 |
| Burst | 2:2, 3:5, 4:1, 8:9 | 5.588 / 55.88% | 5.333 / 6.200 / 5.333 | 95 / 96 | 17 / 18 |
| 짧은 연속 유입 | 2:3, 5:3, 7:3, 8:12 | 6.571 / 65.71% | 6.571 / 6.571 / 6.571 | 138 / 144 | 21 / 27 |

전체 Native는321요청/321 LLM 대상, Batch ON은 Native62요청 + Batch51요청으로321 LLM 대상을 처리했다.
양쪽 각각75건은 Rule 처리다. 이 호출 수 차이를 실제 Token/비용 절감률로 환산하지 않는다.

### poll 분포 — 실제 Batch 분포와 다름

모든 non-empty poll을 합산했다. 빈 poll 수는 raw에 별도 보존했고 아래 분포/평균에 섞지 않았다.

| 시나리오 | Native poll 크기:횟수 | Batch ON poll 크기:횟수 |
|---|---|---|
| 저유입 | 1:24 | 1:24 |
| 일정 간격 | 1:21, 2:10, 3:1, 4:3, 5:2, 6:1 | 1:25, 2:22, 3:1 |
| Burst | 1:1, 2:3, 4:2, 7:1, 8:1, 10:9 | 1:1, 2:2, 4:5, 5:1, 10:9 |
| 짧은 연속 유입 | 1:6, 2:3, 6:3, 10:15 | 1:6, 2:2, 3:1, 7:3, 8:1, 9:2, 10:12 |

Batch ON의 poll10건은 이번 고정 입력에서 Rule2건을 제외하면 Batch8건이다.
따라서 Batch가10을 채우지 않았다는 사실을 유입 부족만으로 설명할 수 없다.
실험은 완료 Skip/중복을 본 부하 입력에 추가하지 않았으며 이 경계는 기존 #59 회귀로 검증했다.

### 반복별 p95 및 변동

단위 ms. run별 분포는 raw의 `summary`와 `raw.samples`에 있다.

| 시나리오 | Native run1 / 2 / 3 | Batch ON run1 / 2 / 3 |
|---|---|---|
| 저유입 | 70.647 / 72.164 / 68.693 | 69.103 / 72.796 / 81.697 |
| 일정 간격 | 212.144 / 212.959 / 280.535 | 69.688 / 72.703 / 70.694 |
| Burst | 548.752 / 528.206 / 548.279 | 80.739 / 76.147 / 80.674 |
| 짧은 연속 유입 | 537.291 / 534.912 / 535.495 | 73.392 / 69.450 / 72.573 |

총3회라 통계적 유의성/신뢰구간은 주장하지 않는다. 저유입 회당8건의 p95는 사실상 최댓값이다.
저유입의 작은 차이를 Batch 자체의 개선/회귀로 확정하지 않는다.

## Lag와 적체 해소

Lag는 `source end offset − group committed offset`. 약100ms 간격 및 시작/종료 시점에 표본을 수집했다.
committed를 먼저, end를 나중에 읽는 **두 Admin RPC는 원자적 snapshot이 아니다**. 표본 최고값은 절대 peak가 아니다.
모든24실행에서 최종 Lag0과 최종 DB completed를 확인했다. DB commit과 Kafka offset commit은 별개다.

| 시나리오 | Native 관측 peak(run1/2/3) | Batch ON 관측 peak | 마지막 발행 ACK 후 Lag0 최초 관측 ms — Native | 동일 — Batch ON |
|---|---|---|---|---|
| 저유입 | 1 / 1 / 1 | 1 / 1 / 1 | 151.674 / 150.466 / 150.232 | 150.910 / 142.023 / 151.930 |
| 일정 간격 | 8 / 8 / 8 | 5 / 5 / 5 | 576.840 / 476.774 / 579.319 | 278.543 / 272.182 / 277.597 |
| Burst | 39 / 39 / 39 | 38 / 39 / 38 | 2304.032 / 2202.507 / 2200.930 | 500.656 / 502.762 / 502.624 |
| 짧은 연속 유입 | 55 / 55 / 55 | 46 / 46 / 45 | 3010.037 / 3005.738 / 3008.629 | 409.780 / 412.909 / 407.551 |

이 표의 Lag0 시간은 raw.lag에서 마지막 ACK 이후 최초로 0을 관측한 표본으로 계산했다.
`drainAfterLastPublishAckMs` 필드는 마지막 **DB commit**까지의 차이이며 Lag0 시각이 아니다.
주 p95에서 제외되는 미poll 대기를 포함한 보조 「발행 ACK→commit」 pooled p95는 Native/Batch ON 순서로
저유입72.268/75.420, 일정 간격459.645/199.608, Burst2140.124/441.006, 짧은 연속2872.847/353.454ms였다.

## 안전 확인·결과 해석

- 본 측정792메시지는 모두 completed. Listener 실패/저장 실패/미완료/Transaction 관측 위반은 모두0.
- Native는 같은 poll의 후속 레코드에서 단건 Provider 대기가 누적된다. Batch는 공유하는 한 번의60ms Fake 대기 후 개별 저장한다.
  이 코드 구조와 이번 관측은 일치하지만 실제 Provider가 건수에 관계없이 같은 지연을 보인다는 증거는 아니다.
- 저유입은 Batch 요청0이며 다음 poll을 모으는 대기가 없다. 현재 underfill Native 경로를 유지하는 근거 중 하나다.
- 일정 간격은 충전율20%에서도 요청 수47대60이었다. Burst/짧은 연속 유입에서는 자연스럽게 묶였다. 충전율만으로 방식의 채택 여부를 결정하지 않는다.
- **현재 Poll-Batch 구조/기본 OFF를 유지하며, Size/Time 도입은 이 결과만으로 결정하지 않는다.**
  특히 Rule 제외에 따른8건 상한과 저유입의 즉시 Native 처리를 구분해 판단해야 한다.
- 후속 Size/Time 비교는 대상 트래픽과 허용 대기 시간/SLO를 Human이 정하고, 추가 대기와 요청 감소를 같은 조건에서 측정할 때 검토한다.
  본 Issue에서는 해당 구현/유료API/운영 활성화를 수행하지 않았다.

## 실행·검증

```bash
# 24실행/792건. 기존 kafka-evidence 전용 task 사용
env -u OPENAI_API_KEY ./gradlew :test --tests '*PollBatchMeasurementsTest' \
  kafkaEvidenceTest --tests '*ChatModerationPollBatchMeasurementTest' --console=plain

# 관련 회귀
env -u OPENAI_API_KEY ./gradlew :test \
  --tests '*ChatModerationBatch*' --tests '*SpringAiModerationBatchAdapterTest' \
  --tests '*ChatModerationServiceTest' --tests '*ModerationRulePolicyTest' \
  --tests '*ChatModerationFinalFailurePersistenceIntegrationTest' \
  --tests '*FakeAiModerationAdapterTest' --tests '*AiModerationPortSelectionTest' \
  --tests '*FakeAiModerationBatchAdapterTest' --tests '*ChatModerationProviderContextTest' \
  --tests '*PollBatchMeasurementsTest' --console=plain

# 전체 회귀 (benchmark는 tag로 제외)
env -u OPENAI_API_KEY ./gradlew test build --console=plain
git diff --check
```

- 2026-10-20 KST 측정: BUILD SUCCESSFUL in56s. 집계 단위4 PASS, Testcontainers 측정1 PASS(내부24실행).
- 관련 회귀: 95 tests / skipped0 / failures0 / errors0, BUILD SUCCESSFUL in50s.
- 2026-10-21 KST 전체 `test build`: BUILD SUCCESSFUL in3m3s. root1,141 tests / skipped86 / failures0 / errors0.
  Lambda11 tests / skipped0 / failures0 / errors0는 UP-TO-DATE 결과. 합계1,152 tests / skipped86 / failures0 / errors0.
  root는 재실행했고 Lambda는 이번 명령에서 재실행하지 않았다. 전용 측정1건은 위 전체 합계에 포함하지 않는다.
- 개발 중 ConsumerConfig 제네릭 타입·protected API 참조의 compile 실패는 테스트 구현 문제로 수정했다.
  Production 장애와 혼동하지 않는다. 측정 원본은 수정 후 실행 결과만 담는다.

## 한계

- 소규모 로컬/고정 Fake60ms/1 partition·concurrency1/H2. 실제 OpenAI의 Batch 크기별 지연·출력 길이·Retry·품질은 미측정이다.
- Chat SEND/Outbox 대기/통신 전체의 E2E가 아니다. 생성→commit은 입력 사전 생성의 영향이 있어 주 비교에 사용하지 않는다.
- 관측 advice/interceptor/100ms Admin 표본 수집 부하를 포함한다. CPU/메모리/JIT/OS 스케줄링을 완전히 분리하지 않았다.
- Broker 대기, poll 내부 대기, DB commit, offset commit은 다르다. 주 E2E와 Lag/보조 지표를 함께 읽어야 한다.
- 장애를 주입한 성능 비교가 아니다. 실패하면 미완료를 정상 표본으로 처리하지 않고 측정 테스트 자체를 실패시킨다.
- benchmark는 일반 test/CI에 포함하지 않고 전용 task로 명시 실행한다. CI 성공을 새로운 부하 측정의 실행 근거로 사용하지 않는다.
- 최적 batchSize, maxWait 필요성, 운영 Token83% 절감, AI 품질 향상, 운영 활성화를 입증하지 않았다.

## 관련

- [Issue #62](https://github.com/gpekd5/bobfull-backend/issues/62) / 上位 [#55](https://github.com/gpekd5/bobfull-backend/issues/55)
- 선행 [PR #61](https://github.com/gpekd5/bobfull-backend/pull/61)
- [기존 Kafka 장애 복구 Evidence](../55-ai-moderation-efficiency/kafka-poll-batch.md)
- 새 PR URL과 최신 Head/CI/Review는 Issue/PR에 기록한다. Merge하지 않고 Draft로 전달한다.
