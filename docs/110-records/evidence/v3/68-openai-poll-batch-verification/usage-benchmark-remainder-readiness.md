# Issue #68 — Usage Benchmark remaining 360 readiness

## 실행 범위와 승인 상태

이번 기록은 **무과금 실행 준비**이며 실제 360건은 NOT_RUN이다. API Key를 읽거나 사용하지 않았다.
이미 완료된 [실제 Burst120](usage-benchmark-burst-live.md)은 재실행하지 않는다.
과거 Gate V1/V2 HOLD 및 Production Batch OFF는 유지한다.
의미상 품질 오류를 기록하며 계속할 수 있는 비운영 사용량 Benchmark일 뿐, 품질 PASS나 운영 채택이 아니다.

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807` 및 Manifest에 기록한 미커밋 소스.
- 승인 ID 후보: `issue68-usage-benchmark-remainder-v1`.
- [승인 Manifest](usage-benchmark-remainder-approval-proposal.json)의 `manifestSha256`:
  `71d0cb040cfa05dab4f425876031951ff86514403dea9a3afb843ce7537e6168`.
- 이는 Java의 Map-key 정렬 직렬화로 계산한 **Manifest 값의 해시**이며 pretty JSON 파일 바이트 해시와는 다르다.
- 테스트 소스 62개 + Production 원본 15개의 해시, GT/metadata/Prompt/Schema 해시를 포함한다.
- 새 Manifest에 대한 별도 Human 유료 승인 전에는 실행하지 않는다. 기존 Burst 승인은 이 단계를 승인하지 않는다.

## 동결 Matrix

각 행은 동일한 과거 합성 diagnostic20을 6조건에서 각각 실행한다. 신규 블라인드 표본이 아니다.
기존 Native/Batch Schema 및 Envelope 차이는 유지하며 A1은 사용하지 않는다.

| 유입 패턴 | 목표 간격 | 동결 조건 실행 순서 | 관측 메시지 수 |
|---|---:|---|---:|
| LOW | 250ms | Native → Poll → Time0 → Time20 → Time50 → Time100 | 120 |
| INTERVAL | 40ms | Poll → Time0 → Time20 → Time50 → Time100 → Native | 120 |
| SHORT | 5ms | Time20 → Time50 → Time100 → Native → Poll → Time0 | 120 |

발행 간격은 목표값이다. 실제 Kafka 수신, Batch 구성/순서/크기, underfill Native 전환은 Raw로 보존한다.
Partition3, concurrency1, max.poll.records10, autoCommit=false, Owner buffer20을 유지한다.
기존 Testcontainers Kafka7.7.1/MySQL8.4 및 실제 Consumer/Adapter/JPA/Owner를 재사용한다.

## Burst 완료 장부 연결

기존 `budget.jsonl`은 읽기 검증만 했으며 이번 준비에서 append/초기화/예약을 하지 않았다.
76개 이벤트와 HTTP36회(이전5 + Burst31), 기존 UNKNOWN이 그대로다.
Private journal/claim/checkpoint는 Git 제출 대상이 아니다.

| 항목 | 현재 값 |
|---|---:|
| 전체 보수적 부담: 정산 + UNKNOWN + 진행 중 | $0.02592300 |
| 알려진 정산액 — cache 할인 미반영 | $0.00664620 |
| 기존 UNKNOWN | $0.01927680 |
| 진행 중 예약 | $0 |
| Benchmark 누적 부담 — Burst cache 미반영 | $0.00604125 |
| gross 예약 이력 — 실제 지출/현재 부담이 아님 | $0.70087680 |

- 현재 장부 SHA: `8aef3df31bbb897987d827f0736dd30619702348866f7fe4ab76e111828fd3ac`.
- 원래 Burst 승인 SHA: `d61581f2947bda099a4277d0e90745c4fe8922032f61e3e1fa706ba27d68a0a9`.
- [Burst 공개 Raw](usage-benchmark-burst-live-results.json) SHA:
  `b9227f2eeddfa89bf679d3b7bb0458a97eb627cf7ad21da40babbe552dca3079`.
- 새로운 Live 진입점은 장부 FileLock을 획득한 뒤 위 SHA/잔액 및 Burst 완료를 검사한다.
  불일치하면 새 claim/예약/HTTP 전에 차단한다.
- 기존 Benchmark 최초 부담 $0.01988175를 기준으로 누적 부담을 계속 계산하며 새 장부를 만들지 않는다.
- REMAINDER claim/실행 디렉터리는 아직 없다. 시작 후 crash/HOLD가 발생하면 자동 재개하지 않는다.

## 호출과 비용 계획 — 실측 또는 보장값 아님

기존 무과금 estimator의 Native20/완전충전 Batch10 형상을 재사용한다.
동결 가격 계산은 input $0.15 / cached input $0.075 / output $0.60 per1M이다.
이번 준비에서는 모델 접근이나 가격을 유료 API로 재확인하지 않았다.

| 형상 | 정상 논리 요청 | 추정 Input | 최대 출력 cap 합계 | cache 미반영 추정 비용 |
|---|---:|---:|---:|---:|
| 비Native 완전충전 | 90 | 106,092 | 46,080 | $0.04356180 |
| 전부 underfill 단건 | 360 | 374,742 | 46,080 | $0.08385930 |

위 입력값은 tokenizer 추정이며 Provider 청구 Usage가 아니다. Native60회 + 비Native30~300회로
정상 논리 요청 범위는 90~360회다. 중간 크기 Batch/Envelope로 실제 Input은 두 형상 사이에 있다고 보장하지 않는다.
실제 Provider 지연이 Fake60ms와 달라 poll 적체 및 Batch 구성이 달라질 수 있다.
Cache 적중은 보장하지 않으며 실제 Usage에서 확인하고 할인 반영/미반영 비용을 각각 기록한다.

- 이 단계 신규 부담 상한 **$0.40**, Benchmark 전체 **$0.50**, 기존 부담 포함 전체 **$1.00**.
- 논리 요청당 최대 HTTP4, 이 단계 최대 HTTP1,440, Burst 포함 Benchmark 최대 HTTP1,920.
- 현재 Burst HTTP31회도 전체 카운터에 이월한다. 상한은 목표 호출 수가 아니다.
- 같은 두 추정 형상의 4회 전송 비용은 $0.17424720~$0.33543720이지만 보장 상한은 아니다.
  모든 전송에 input context128K를 가정한 이론적 비용 $27.758592는 예산을 넘는다.
  따라서 모든 HTTP를 허용하는 대신 **예약 부족 시 실제 전송 전에 중단**한다.
- 각 exchange 직전 출력 cap에 따라 약 $0.0192768~$0.019968을 원자적 선예약/fsync한다.
  정상 Usage는 cache 할인 미반영 금액으로 정산하고 UNKNOWN/미정산은 반환하지 않는다.
- 정상 추정 비용은 $0.40보다 작아 360건 실행을 계획할 수 있으나 완료를 보장하지 않는다.
  Usage 불명, 큰 사용량, Retry, 보수적 다음 예약 여유 부족 또는 기술 오류가 조기 중단 조건이다.
- 이 단계가 $0.40을 모두 부담해도 현재값 기준 Benchmark $0.40604125 / 전체 $0.425923이다.
  gross 이력 $0.7008768을 실제 사용액으로 더하지 않는다.

## 최소 코드 변경

- `Issue68UsageBenchmarkPlan`: 나머지 단계에 실제 Burst 완료 장부/공개 결과/잔액을 명시.
- `Issue68Budget`: 잠금 안에서 완료 Burst와 동일한 장부인지 검사하는 Live 진입 경계 추가.
- `Issue68UsageBenchmarkSession`: 별도 승인 ID와 SHA를 요구하는 `openRemainder()` 추가.
- `Issue68HttpBoundary`: Benchmark 단계에만 승인된 1,440회 ceiling을 적용. 다른 Live 계약은 넓히지 않음.
- 기존 Live 실행 본문을 공통 함수로 재사용하고, REMAINDER만 실행하는 별도 opt-in 테스트 추가.
- REMAINDER 전용 무과금 회귀 11건 추가. Production 및 기존 Dashboard는 변경하지 않음.

HTTPS443 고정 목적지/noProxy/noRedirect, 실제 Payload/옵션/Schema/ID 매핑 검사,
네트워크 exchange별 예산/횟수 검사, Usage 불명 시 동일 논리 Retry까지 차단하는 계약은 유지한다.
각 조건의 원본 결과, 요청 구성/Token/SDK/HTTP, UTC 시작·종료, 개별 DB afterCommit,
Lag/Offset/DLT/Buffer와 FP/FN/Category/Risk/Critical 오류를 기존 checkpoint에 보존한다.
기술 오류는 즉시 중단하며 다음 조건/단계로 자동 진입하지 않는다.

## 무과금 검증 결과

[신규 결과](usage-benchmark-remainder-free-results.json): 70건 중 **68 PASS / 2 paid entry SKIP / 실패·오류0**.
compileTestJava 및 선택 test task SUCCESS, 2m21s.

- 공개된 Burst 회계 자료만으로 동일 SHA의 76이벤트를 temp fixture에 복원.
  현재 장부 잔액/UNKNOWN 보존, 잠금, 중복 실행/재시작/불완전 이력/변조/승인 불일치 차단 검증.
- 18조건/360건/HTTP1,440, 논리당4회, $0.40 초과 선예약 차단 검증.
- 실제 전송 없는 HTTPS inspection으로 REMAINDER 목적지/계약 검사.
- Fake UNKNOWN 응답에서 첫 요청 취소 및 같은 논리 Retry 차단/예약 보존 검증.
- 변경된 단계 연결 때문에 기존 Fake480/24조건 Kafka/MySQL 및 오류 주입 테스트를 재실행:
  각 조건 DB20, Lag0, 기술 정합성 통과. **실제 Burst를 재실행한 것이 아니다.**
- 처음 UNKNOWN 테스트의 HTTP200 반환 기대가 기존 취소 동작과 달라 실패했으며,
  기대값을 IOException으로 고쳐 최종 재실행. 안전장치 코드는 완화하지 않았다.
- 기존 전체 회귀 root1,474건(93 skipped) + Lambda11건 / 실패0 및 build SUCCESS는
  [기존 기록](usage-benchmark-free-results.json)을 재사용한다. 이번에 전체 build를 재실행했다고 주장하지 않는다.
- 기존 Evidence/private/Production/Grafana 867파일 해시 불변. 실제 journal76줄 및 SHA 불변.
- `git diff --check` PASS. 유료 API/Key 접근/Commit/Push/PR/Issue 변경 없음.

무과금 재현: Key 및 모든 Live 승인 환경변수를 제거한 자식 프로세스에서 다음 테스트만 선택한다.

```sh
ruby -e 'ENV.keys.grep(/\A(?:OPENAI_API_KEY|ISSUE68_.*)\z/).each{|k|ENV.delete(k)};exec "./gradlew", ":test", "--tests", "*Issue68UsageBenchmarkRemainderSafetyTest", "--tests", "*Issue68UsageBenchmarkSafetyTest", "--tests", "*Issue68UsageBenchmarkLiveTest", "--tests", "*Issue68UsageBenchmarkRemainderLiveTest", "--tests", "*Issue68ExpandedHttpSafetyTest", "--tests", "*Issue68ExpandedLiveSafetyTest", "--tests", "*Issue68UsageBenchmarkKafkaTest", "--no-daemon", "--console=plain"'
```

## Grafana 간단 점검 — Benchmark 차단 사유 아님

기존 Grafana health OK, Prometheus target `host.docker.internal:8080/actuator/prometheus` UP,
기존 Dashboard 호출 수 쿼리는 여전히19다. 기본 scrape는15s이고 현재 테스트 target은 없다.
Live 테스트 Context에는 exporter가 없으며 기존 `fakeMonitoring()`과 `Issue68MonitoringBridge`는
Live 사용을 차단하거나 `issue68_fake`로 표시한다. 이를 그대로 연결하면 잘못된 출처 표시가 된다.
따라서 **현재 연결로 Live 값을 기존 Dashboard에 직접 표시할 수 없다**.
이번 단계에서는 차단을 풀거나 Dashboard/datasource/Prometheus 설정을 변경하지 않았다.
조건별 UTC 및 Raw 관측은 유지하므로 이 제한 때문에 핵심 Benchmark를 지연시키지 않는다.
Live 캡처는 NOT_CONNECTED/NOT_TAKEN이며 Fake 캡처를 Live 증거로 재사용하지 않는다.
기존 MySQL datasource도 격리된 Testcontainers DB와 별개다.

## 유료 실행 전 남은 조건

새 Manifest SHA에 대한 **Human 명시적 승인만 대기**한다. 자동 실행하지 않는다.
승인 이후에도 매 실행/전송에서 소스/승인/장부 일치, 단일 잠금, 모델 응답/Usage와 모든 기술 Gate를
다시 검사한다. 모델 접근 실패나 기술 HOLD 시 부분 결과를 보존하고 추가 전송/자동 재개를 금지한다.
기존 품질 HOLD 해제, A1/N2/Control 재실험, 운영 활성화 및 #68/#55 종료는 이 승인 범위가 아니다.
