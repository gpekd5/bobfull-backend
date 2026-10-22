# Issue #68 — 실제 OpenAI 480관측 Usage Benchmark

## 최종 판정

**비운영 사용량 Benchmark 480관측 완료. 기술 정합성 통과, 품질 통과 또는 운영 채택 아님.**
기존 Gate V1/V2 HOLD 및 Control/A1 실패 이력을 유지하고 Production Batch OFF를 유지한다.
의미상 오류를 기록하며 계속하는 이번 Benchmark 계약과 품질 평가 Gate를 혼동하지 않는다.

동일한 과거 합성 diagnostic20을 6조건×4패턴에 재사용했다.
**480개의 서로 다른 메시지/신규 블라인드 평가가 아니며, 조건별80관측 역시 Held-out80 전체가 아니다.**
각 조건×트래픽은1회 측정이다. 동일 요청 반복 안정성이나 모집단 오류율을 입증하지 않는다.

## 승인 및 실행

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`; 동결 미커밋 소스77개 일치.
- Burst 승인 SHA: `d61581f2947bda099a4277d0e90745c4fe8922032f61e3e1fa706ba27d68a0a9`.
  [기존 Burst120](usage-benchmark-burst-live.md)은 재실행하지 않았다.
- 나머지360 승인 ID: `issue68-usage-benchmark-remainder-v1`.
  [Manifest](usage-benchmark-remainder-approval-proposal.json) SHA:
  `71d0cb040cfa05dab4f425876031951ff86514403dea9a3afb843ce7537e6168`.
- 나머지360 실행은 **1회**, 18조건 모두 완료. Gradle4m18s, paid opt-in 테스트1건 PASS/skip0/실패0.
- 나머지 조건별 요청 시작~마지막 조건 종료:
  **2026-10-22 13:21:14.183699~13:25:19.645536 UTC (22:21:14~22:25:19 KST)**.
- Burst 실행은 같은 날10:34~10:35 UTC의 별도 세션이다. 두 세션 사이 공백을 처리시간에 포함하지 않는다.
- Production System Prompt/Policy v2/Rule/gpt-4o-mini 유지. 실제 응답 모델은 모두
  `gpt-4o-mini-2024-07-18`. A1 미사용.
- Native와 Batch는 기존 Schema/Envelope 차이를 그대로 유지한다.
- 격리 Kafka7.7.1/MySQL8.4 Testcontainers 및 실제 Consumer/Adapter/JPA/테스트용 Owner 사용.
  partition3, concurrency1, max.poll.records10, autoCommit=false, Owner buffer20.
- 목표 발행 간격250/40/0/5ms. 실제 수신/묶음/순서/발행 ACK와 요청 Payload SHA는 Raw에 보존한다.
- API Key는 .env의 해당 항목만 읽어 실행 자식 환경에 일시 전달했다. 전체 .env source/다른 항목 주입은 하지 않았고,
  Key 값은 명령 인수/로그/Evidence에 저장하지 않았다.

## 원본 및 계산 근거

- [Burst Raw](usage-benchmark-burst-live-results.json): 기존 파일 불변.
- [LOW Raw](usage-benchmark-low-live-results.json), [INTERVAL Raw](usage-benchmark-interval-live-results.json),
  [SHORT Raw](usage-benchmark-short-live-results.json): 실제18조건의 unit-result checkpoint를 그대로 비식별 공개.
- [480관측 집계 및 Case 비교](usage-benchmark-480-live-results.json): 각 트래픽의 Native 기준으로 비교.
  `comparisons`에는 모든 GT 불일치, Native 대비 Case 변화, 새 FP/FN 및 Critical 원본 평가를 보존한다.
- Private journal/claim/checkpoint는 Git에 복사하지 않는다.
  나머지 세션 최종 checkpoint basename `01158-final.json`,
  SHA `949168446ac9fd7389c59398444b04ca45ef4373c0cd88e4734a044645a55ab7`.
- 정확한 계산은 Raw Usage와 개별 timestamp 기준이다. Grafana 캡처로 절감률을 계산하지 않는다.

## 480관측 — 실제 Usage와 추정 비용

| 조건(각80관측) | 논리/HTTP | Input | Cached | Output | Total | 비용 미할인 USD | 비용 할인 USD |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Native | 80/80 | 65756 | 0 | 1416 | 67172 | $0.01071300 | $0.01071300 |
| Poll | 14/14 | 14274 | 3072 | 1995 | 16269 | $0.00333810 | $0.00310770 |
| Size/Time 0ms | 15/15 | 15221 | 0 | 2003 | 17224 | $0.00348495 | $0.00348495 |
| Size/Time 20ms | 14/14 | 14274 | 1024 | 1972 | 16246 | $0.00332430 | $0.00324750 |
| Size/Time 50ms | 12/12 | 12670 | 1024 | 1954 | 14624 | $0.00307290 | $0.00299610 |
| Size/Time 100ms | 13/13 | 13472 | 2048 | 1958 | 15430 | $0.00319560 | $0.00304200 |

전체 **148논리/148HTTP**, Input135,667 / Cached7,168 / Output11,298 / Total146,965.
전체 비용은 cache 미반영 **$0.02712885**, 반영 **$0.02659125**다.
이번 나머지360은 **117논리/117HTTP**, Input106,816 / Cached3,072 / Output8,442 / Total115,258,
비용 미반영 **$0.02108760**, 반영 **$0.02085720**다.

단가는 [공식 gpt-4o-mini 문서](https://developers.openai.com/api/docs/models/gpt-4o-mini)의
input$0.15 / cached input$0.075 / output$0.60 per1M이며 이번 실행 전 재확인했다.
여기서 비용은 실제 Usage 기반 추정이며 청구서/실제 결제액 검증이 아니다.
Cached는 Input의 부분집합이다. Total에 다시 더하지 않는다.
Usage는 HTTP별 한 번만 집계하고 Batch Usage를 개별 메시지에 복제/임의 배분하지 않았다.
이번에는 Retry가 없어 SDK와 HTTP Usage148건 모두 일치했다.

Native 대비 관측 합산 Token 감소는 Poll75.78%, Time0 74.36%, Time20 75.81%, Time50 78.23%, Time100 77.03%다.
cache 미반영 비용 감소는 각각68.84%,67.47%,68.97%,71.32%,70.17%이며,
할인 반영은70.99%,67.47%,69.69%,72.03%,71.60%다.
모든 Batch 방식은 Output이 Native보다 커졌지만 반복 Input 분산 효과로 Total이 감소했다.
이는 이번 고정 진단 표본과 관측된 묶음에 대한 결과이며 운영 절감률이 아니다.

## 실제 Batch 크기

| 조건 | Native/Batch 논리 | 요청크기 분포(크기×횟수) | 모든 요청 평균크기 | Batch 요청 충전율 |
| --- | --- | --- | --- | --- |
| Native | 80/0 | 1×80 | 1.000 | N/A |
| Poll | 4/10 | 1×4, 2×1, 4×1, 5×1, 7×1, 8×1, 10×5 | 5.714 | 76.0% |
| Size/Time 0ms | 4/11 | 1×4, 2×1, 4×2, 5×1, 6×1, 7×1, 8×1, 10×4 | 5.333 | 69.1% |
| Size/Time 20ms | 4/10 | 1×4, 3×1, 4×1, 5×1, 6×1, 8×1, 10×5 | 5.714 | 76.0% |
| Size/Time 50ms | 2/10 | 1×2, 2×2, 7×2, 10×6 | 6.667 | 78.0% |
| Size/Time 100ms | 3/10 | 1×3, 3×2, 5×1, 6×1, 10×6 | 6.154 | 77.0% |

충전율은 **2건 이상 실제 Batch 요청만** 대상으로 평균크기/10이다.
1건 Native underfill 요청도 분포와 Native/Batch 논리에 별도로 포함한다.
실제 가장 큰 요청은10건, Owner 최대 Buffer는11건으로 승인된20건 상한 안이었다.
저유입에서도 Provider 처리 중 적체된 메시지가 다음 poll에서 묶이므로250ms라는 발행 목표만으로
Native-only라고 가정할 수 없다. 반대로 모든 Batch가10건이라고 가정하지 않는다.

## 품질 — 기존 GT 그대로

| 조건 | Result/Category/Risk exact(/80) | TP/TN/FP/FN | F1 | GT HIGH 하향 | 검토신호 누락 | Critical 실패 | Native 대비 새 FP/FN |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Native | 53/53/42 | 36/17/27/0 | 0.727 | 15 | 0 | 9 | 0/0 |
| Poll | 63/63/45 | 35/28/16/1 | 0.805 | 17 | 1 | 9 | 3/1 |
| Size/Time 0ms | 63/63/46 | 35/28/16/1 | 0.805 | 18 | 1 | 10 | 2/1 |
| Size/Time 20ms | 62/62/47 | 33/29/15/3 | 0.786 | 16 | 3 | 9 | 2/3 |
| Size/Time 50ms | 67/67/53 | 33/34/10/3 | 0.835 | 15 | 3 | 8 | 1/3 |
| Size/Time 100ms | 61/61/51 | 31/30/14/5 | 0.765 | 15 | 5 | 8 | 3/5 |

Critical 실패는 기존 Gate V2의 `CRITICAL_*` 사유가 하나 이상 있는 **Case 관측 건수**다.
사유 수 합계나 고유 메시지 수가 아니다. HIGH 하향/Review 누락은 Critical과 중복될 수 있다.
Result/Category/Risk는 각각 독립적인 exact 지표이며 전체 점수만으로 품질 통과 판정하지 않는다.

- 전체 TP203 / TN166 / FP98 / FN13, Result/Category369/480, Risk284/480.
- FN13과 검토 신호 누락13은 모두 같은 트래픽 Native 대비 새 FN이다. Native FN은0이다.
- Native도 FP27, Critical 실패9가 있어 완벽한 기준선이 아니다.
- Batch의 FP 총계가 작아도 새 FP가 존재한다. Native 대비 새 FP는11관측이며
  Case ID는 SAFE-17 및 PROFANITY-05다. 기존 FP 개선과 새 FP를 상쇄해 숨기지 않는다.
- PROFANITY-14는 Native4관측 모두 FLAGGED/MEDIUM이며 공통 Critical 위험도 오류다.
  추가로 Batch에서 SAFE/LOW5관측이 발생했다. 이는 단순 HIGH→MEDIUM과 다른 검토 신호 누락이다.
- 전체 FN은 PROFANITY-14의5관측과 PROFANITY-16의8관측이다.
- Critical 실패53관측: PROFANITY-14 23, PROFANITY-08 10, PROFANITY-15 14, SPAM-08 6.
- 정책상 PROFANITY LOW 미사용과 불일치하는 응답도5관측(Native4, Time100 1) 기록했다.
  구조적 Validator 통과와 의미상 정책 준수는 다르며 오류를 정상 품질로 처리하지 않는다.

### 같은 트래픽 Native 대비 새 FN

| 트래픽 | 조건 | Case ID |
| --- | --- | --- |
| LOW | Poll | PROFANITY-16 |
| LOW | Size/Time 20ms | PROFANITY-14, PROFANITY-16 |
| LOW | Size/Time 100ms | PROFANITY-16 |
| INTERVAL | Size/Time 100ms | PROFANITY-14 |
| BURST | Size/Time 50ms | PROFANITY-16 |
| BURST | Size/Time 100ms | PROFANITY-16 |
| SHORT | Size/Time 20ms | PROFANITY-14 |
| SHORT | Size/Time 50ms | PROFANITY-14, PROFANITY-16 |
| SHORT | Size/Time 100ms | PROFANITY-14, PROFANITY-16 |
| SHORT | Size/Time 0ms | PROFANITY-16 |

### 같은 트래픽 Native 대비 새 FP

| 트래픽 | 조건 | Case ID |
| --- | --- | --- |
| LOW | Poll | PROFANITY-05 |
| LOW | Size/Time 0ms | PROFANITY-05 |
| LOW | Size/Time 100ms | PROFANITY-05 |
| INTERVAL | Poll | SAFE-17, PROFANITY-05 |
| INTERVAL | Size/Time 0ms | SAFE-17 |
| INTERVAL | Size/Time 20ms | SAFE-17 |
| INTERVAL | Size/Time 50ms | SAFE-17 |
| INTERVAL | Size/Time 100ms | SAFE-17, PROFANITY-05 |
| SHORT | Size/Time 20ms | PROFANITY-05 |

### 주요 Case의 실제 Result/Risk

F=FLAGGED, S=SAFE. 모든 행은 원래 GT HIGH를 유지한다.

| 트래픽 | Case | Native | Poll | Time0 | Time20 | Time50 | Time100 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| LOW | PROFANITY-14 | F/MEDIUM | F/MEDIUM | F/MEDIUM | S/LOW | F/MEDIUM | F/MEDIUM |
| LOW | PROFANITY-16 | F/HIGH | S/LOW | F/MEDIUM | S/LOW | F/MEDIUM | S/LOW |
| INTERVAL | PROFANITY-14 | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM | S/LOW |
| INTERVAL | PROFANITY-16 | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM |
| BURST | PROFANITY-14 | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM |
| BURST | PROFANITY-16 | F/MEDIUM | F/MEDIUM | F/MEDIUM | F/MEDIUM | S/LOW | S/LOW |
| SHORT | PROFANITY-14 | F/MEDIUM | F/HIGH | F/MEDIUM | S/LOW | S/LOW | S/LOW |
| SHORT | PROFANITY-16 | F/HIGH | F/MEDIUM | S/LOW | F/MEDIUM | S/LOW | S/LOW |

Native PROFANITY-16도 패턴별 실행에서 HIGH/MEDIUM이 달랐다.
Batch의 SAFE 전환은 직접 관측했지만 요청 형식·동반 구성·순서·모델 변동의 인과 기여는 이 실험만으로 분리하지 못한다.
특정 조건에서 정답이 나왔다는 이유로 원인이 해결됐다고 판단하지 않는다.

## 지연 — 합산80관측/조건, seconds

| 조건 | 수신→Commit p50/p95(s) | ACK→Commit p50/p95(s) | Provider p50/p95(s) | Buffer avg/p95/max(ms) |
| --- | --- | --- | --- | --- |
| Native | 5.031 / 10.493 | 11.925 / 20.853 | 1.027 / 1.549 | N/A |
| Poll | 4.171 / 5.114 | 6.579 / 10.212 | 2.708 / 4.915 | N/A |
| Size/Time 0ms | 3.776 / 8.575 | 7.263 / 15.702 | 3.236 / 8.399 | 0.121/0.553/0.574 |
| Size/Time 20ms | 4.045 / 4.808 | 6.407 / 10.194 | 2.748 / 4.633 | 112.320/20.399/4325.772 |
| Size/Time 50ms | 4.096 / 4.703 | 5.788 / 9.509 | 3.428 / 4.563 | 65.933/50.531/4120.427 |
| Size/Time 100ms | 4.241 / 5.375 | 6.459 / 9.951 | 3.079 / 5.243 | 149.032/101.036/5395.467 |

각 조건의 p50/p95는 4패턴의 개별 Case timestamp80개에서 nearest-rank로 재계산했다.
조건별 p95의 평균이 아니다. Provider도 원래 논리 요청별 표본에서 계산한다.
수신 시각은 Consumer가 poll에서 최초 관측한 시각으로, poll 이전 Broker 대기는 제외된다.
따라서 발행 ACK→Commit도 같이 비교한다. save/flush 반환이 아니라 개별 transaction afterCommit을 사용한다.
Provider 논리 시간에는 Adapter/SDK/계측·장부 정산 overhead가 포함된다.

**maxWait는 hard Buffer/E2E SLO가 아니다.** 실제 Buffer 최대 대기는5,395.467ms였다.
단일 Owner는 동기 Provider 처리 중 남은 Buffer의 Deadline을 재평가할 수 없고,
이후 loop에서 다음 Flush를 처리한다. 실제 Buffer11건에서10건 처리 뒤 남은 항목이 기다릴 수 있다.
이 구조는 현재 코드의 동기 `process() → retry.analyze() → service.analyzePrepared()`와 일치한다.
p95가20/50/100ms 근처여도 평균/최대값을 숨기면 안 된다. 여기서는 수정하지 않고 운영상 제한으로 남긴다.

## 트래픽 × 조건 상세

각 행은20관측이다. DB20/최종Lag0/DLT0/저장실패0/unsafeCommit0은24조건 모두 동일하다.
조건별 UTC 시작/종료, 실제 HTTP 경과시간, poll/lag 표본, Provider 요청별 Case 순서/해시는 Raw를 따른다.

### LOW — 목표250ms

| 조건 | 논리/HTTP | Input/Cached/Output/Total | 비용 미할인/할인 USD | 요청크기 분포 | FP/FN | Critical |
| --- | --- | --- | --- | --- | --- | --- |
| Native | 20/20 | 16439/0/355/16794 | $0.00267885 / $0.00267885 | 1×20 | 7/0 | 2 |
| Poll | 4/4 | 4042/0/502/4544 | $0.00090750 / $0.00090750 | 1×1, 4×1, 5×1, 10×1 | 5/1 | 3 |
| Size/Time 0ms | 4/4 | 4042/0/511/4553 | $0.00091290 / $0.00091290 | 1×1, 4×1, 5×1, 10×1 | 5/0 | 4 |
| Size/Time 20ms | 4/4 | 4042/0/491/4533 | $0.00090090 / $0.00090090 | 1×1, 4×1, 5×1, 10×1 | 5/2 | 1 |
| Size/Time 50ms | 4/4 | 4042/0/496/4538 | $0.00090390 / $0.00090390 | 1×1, 2×1, 7×1, 10×1 | 3/0 | 3 |
| Size/Time 100ms | 5/5 | 4844/0/488/5332 | $0.00101940 / $0.00101940 | 1×2, 3×1, 5×1, 10×1 | 4/1 | 3 |

| 조건 | 수신→Commit p50/p95(s) | ACK→Commit p50/p95(s) | Provider p50/p95(s) | Buffer p95/max(ms) |
| --- | --- | --- | --- | --- |
| Native | 4.599 / 11.716 | 13.880 / 20.703 | 1.076 / 1.778 | N/A |
| Poll | 2.815 / 4.711 | 5.629 / 7.033 | 2.449 / 4.530 | N/A |
| Size/Time 0ms | 2.829 / 5.337 | 6.444 / 7.791 | 2.407 / 5.151 | 0.054/0.339 |
| Size/Time 20ms | 2.895 / 4.822 | 6.026 / 7.495 | 2.293 / 4.633 | 20.197/20.399 |
| Size/Time 50ms | 4.316 / 4.745 | 6.681 / 8.731 | 1.733 / 4.563 | 50.532/50.534 |
| Size/Time 100ms | 5.281 / 5.403 | 6.964 / 8.760 | 1.930 / 5.243 | 101.041/5395.467 |

### INTERVAL — 목표40ms

| 조건 | 논리/HTTP | Input/Cached/Output/Total | 비용 미할인/할인 USD | 요청크기 분포 | FP/FN | Critical |
| --- | --- | --- | --- | --- | --- | --- |
| Native | 20/20 | 16439/0/349/16788 | $0.00267525 / $0.00267525 | 1×20 | 6/0 | 2 |
| Poll | 4/4 | 3897/0/497/4394 | $0.00088275 / $0.00088275 | 1×2, 8×1, 10×1 | 5/0 | 3 |
| Size/Time 0ms | 4/4 | 3897/0/480/4377 | $0.00087255 / $0.00087255 | 1×2, 8×1, 10×1 | 2/0 | 3 |
| Size/Time 20ms | 4/4 | 3897/1024/480/4377 | $0.00087255 / $0.00079575 | 1×2, 8×1, 10×1 | 2/0 | 3 |
| Size/Time 50ms | 4/4 | 4042/0/494/4536 | $0.00090270 / $0.00090270 | 1×1, 2×1, 7×1, 10×1 | 3/0 | 1 |
| Size/Time 100ms | 4/4 | 4042/0/499/4541 | $0.00090570 / $0.00090570 | 1×1, 3×1, 6×1, 10×1 | 5/1 | 1 |

| 조건 | 수신→Commit p50/p95(s) | ACK→Commit p50/p95(s) | Provider p50/p95(s) | Buffer p95/max(ms) |
| --- | --- | --- | --- | --- |
| Native | 4.659 / 9.868 | 10.018 / 20.467 | 1.060 / 1.315 | N/A |
| Poll | 4.171 / 4.508 | 6.396 / 10.524 | 1.061 / 4.304 | N/A |
| Size/Time 0ms | 3.763 / 3.967 | 5.997 / 9.671 | 1.240 / 3.740 | 0.072/0.096 |
| Size/Time 20ms | 4.184 / 4.340 | 5.322 / 10.392 | 1.155 / 4.136 | 29.075/4325.772 |
| Size/Time 50ms | 3.996 / 4.128 | 5.483 / 9.743 | 1.523 / 3.964 | 50.054/4120.427 |
| Size/Time 100ms | 4.380 / 4.551 | 6.349 / 10.247 | 1.966 / 4.353 | 101.110/4548.880 |

### BURST — 목표0ms, 과거 완료 결과 재사용

| 조건 | 논리/HTTP | Input/Cached/Output/Total | 비용 미할인/할인 USD | 요청크기 분포 | FP/FN | Critical |
| --- | --- | --- | --- | --- | --- | --- |
| Native | 20/20 | 16439/0/356/16795 | $0.00267945 / $0.00267945 | 1×20 | 7/0 | 2 |
| Poll | 2/2 | 2293/2048/507/2800 | $0.00064815 / $0.00049455 | 10×2 | 4/0 | 3 |
| Size/Time 0ms | 3/3 | 3240/0/517/3757 | $0.00079620 / $0.00079620 | 4×1, 6×1, 10×1 | 5/0 | 2 |
| Size/Time 20ms | 2/2 | 2293/0/498/2791 | $0.00064275 / $0.00064275 | 10×2 | 3/0 | 2 |
| Size/Time 50ms | 2/2 | 2293/1024/485/2778 | $0.00063495 / $0.00055815 | 10×2 | 2/1 | 2 |
| Size/Time 100ms | 2/2 | 2293/1024/493/2786 | $0.00063975 / $0.00056295 | 10×2 | 3/1 | 3 |

| 조건 | 수신→Commit p50/p95(s) | ACK→Commit p50/p95(s) | Provider p50/p95(s) | Buffer p95/max(ms) |
| --- | --- | --- | --- | --- |
| Native | 6.011 / 10.819 | 11.925 / 21.109 | 1.012 / 1.465 | N/A |
| Poll | 4.395 / 5.176 | 4.397 / 9.591 | 4.202 / 4.915 | N/A |
| Size/Time 0ms | 3.776 / 8.615 | 8.643 / 15.764 | 3.628 / 8.399 | 0.556/0.574 |
| Size/Time 20ms | 4.100 / 4.510 | 4.535 / 8.629 | 3.892 / 4.347 | 0.039/0.040 |
| Size/Time 50ms | 3.648 / 4.592 | 3.650 / 8.255 | 3.428 / 4.439 | 0.040/0.041 |
| Size/Time 100ms | 4.326 / 4.892 | 4.328 / 9.239 | 4.171 / 4.726 | 0.058/0.059 |

### SHORT — 목표5ms

| 조건 | 논리/HTTP | Input/Cached/Output/Total | 비용 미할인/할인 USD | 요청크기 분포 | FP/FN | Critical |
| --- | --- | --- | --- | --- | --- | --- |
| Native | 20/20 | 16439/0/356/16795 | $0.00267945 / $0.00267945 | 1×20 | 7/0 | 3 |
| Poll | 4/4 | 4042/1024/489/4531 | $0.00089970 / $0.00082290 | 1×1, 2×1, 7×1, 10×1 | 2/0 | 0 |
| Size/Time 0ms | 4/4 | 4042/0/495/4537 | $0.00090330 / $0.00090330 | 1×1, 2×1, 7×1, 10×1 | 4/1 | 1 |
| Size/Time 20ms | 4/4 | 4042/0/503/4545 | $0.00090810 / $0.00090810 | 1×1, 3×1, 6×1, 10×1 | 5/1 | 3 |
| Size/Time 50ms | 2/2 | 2293/0/479/2772 | $0.00063135 / $0.00063135 | 10×2 | 2/2 | 2 |
| Size/Time 100ms | 2/2 | 2293/1024/478/2771 | $0.00063075 / $0.00055395 | 10×2 | 2/2 | 1 |

| 조건 | 수신→Commit p50/p95(s) | ACK→Commit p50/p95(s) | Provider p50/p95(s) | Buffer p95/max(ms) |
| --- | --- | --- | --- | --- |
| Native | 3.939 / 9.164 | 10.990 / 19.867 | 0.968 / 1.287 | N/A |
| Poll | 3.074 / 4.050 | 6.675 / 9.768 | 1.453 / 3.854 | N/A |
| Size/Time 0ms | 3.485 / 4.324 | 7.138 / 10.652 | 1.664 / 4.111 | 0.097/0.099 |
| Size/Time 20ms | 3.941 / 4.109 | 6.891 / 9.911 | 1.887 / 3.883 | 37.544/4088.648 |
| Size/Time 50ms | 3.945 / 4.487 | 3.946 / 8.421 | 3.795 / 4.286 | 32.043/32.043 |
| Size/Time 100ms | 4.172 / 4.254 | 4.255 / 8.489 | 4.032 / 4.040 | 33.885/33.886 |

## 예산 및 장부 보존

| 항목 | 실행 후 값 |
|---|---:|
| 이번 단계 부담 — cache 미반영 정산 | $0.02108760 / 상한$0.40 |
| Benchmark 전체 부담 — Burst 포함 | $0.02712885 / 상한$0.50 |
| 이전 정산 + 전체 Benchmark 정산 | $0.02773380 |
| 기존 UNKNOWN — 임의 해제 없음 | $0.01927680 |
| 진행 중 예약 | $0 |
| 전체 장부 부담 | $0.04701060 / 상한$1.00 |
| 전체 이력 중 최대 동시 부담 | $0.06648180 |
| gross 예약 이력 | $2.97492480 |

gross는 과거 각 HTTP의 선예약을 단순 합한 기록이며 실제 지출이나 현재 부담이 아니다.
예산을 초기화하거나 기존 UNKNOWN을 소급 정산하지 않았다. 실제 전송 직전 예약/fsync와
알려진 Usage의 보수적 정산을 반복했다. 새 UNKNOWN0, 미확정 진행 예약0, 중복 정산0이다.
전역 HTTP153회는 이전5회 + Burst31회 + 이번117회다.
이번 단계117회는1,440회 상한, Benchmark148회는1,920회 상한 안이며 논리당 모두1회였다.

- 기존76줄 prefix SHA `8aef3df31bbb897987d827f0736dd30619702348866f7fe4ab76e111828fd3ac` 불변.
- 현재312이벤트 장부 SHA `63a30ae34cbf3b07773c9f257a2107bcbbf7b33d08a85c35550bf300242952a1`.
- 기존932파일 비교에서 예상된 journal append만 변경. 과거 Evidence/private checkpoint/GT/Production/Grafana 불변.
- journal hash-chain 및 소스77개·GT·Model·Prompt·Schema·Production 계약 불변 검증 PASS.

## 기술 검증과 한계

- 실제 paid opt-in 테스트1 PASS, 실패/오류0, SKIP0. Gradle test SUCCESS(4m18s).
- 요청/응답 Mapping, Parse, Validator, HTTP/SDK Usage, 개별 DB Commit 및 최종 Offset 정합성 통과.
- 전체 HTTP200/SETTLED148, HTTP/SDK Usage 일치148, Provider 실패0/새 UNKNOWN0.
- DB480완료, 최종Lag 모두0, DLT0, 저장실패0, 트랜잭션 경계 위반0, unsafeCommit0,
  각 메시지 전달횟수1, Owner retry0/thread위반0. 관측 Lag 최대20.
- 기존 무과금70건(유료2 SKIP)/실패0과 root1,474(93skip)+Lambda11/실패0 및 전체Build SUCCESS는
  [기존 검증](usage-benchmark-remainder-free-results.json)에서 재사용했다.
  이번 유료 실행 뒤 전체 Build를 다시 실행했다고 표현하지 않는다.
- 이번 정상 실행에서 장애/강제종료/재균형을 새로 주입하지 않았다.
  Retry/recovery-only/DLT 실패 경계는 기존 Fake 회귀 근거와 구분한다.
- 원래 트래픽별 Case/GT20 및 정책을 유지했지만 실제 poll 구성은 달랐다.
  조건 순서 회전, cache hit, cold connection, 시간대, 모델 변동을 제거하지 못했다.
- Fake60ms 실험을 실제 OpenAI 수치와 혼합하지 않는다. 추가 반복/블라인드 GT 검증은 하지 않았다.
- Grafana Live exporter는 연결하지 않았다. Dashboard/패널/datasource는 변경하지 않았고 캡처도 만들지 않았다.
  Raw를 공식 결과로 사용했으므로 모니터링 연결 문제로 유료 측정을 지연하지 않았다.

## 사후 검수 서비스에서의 오류 영향

실제 코드 흐름과 근거는 [최신 Evidence 색인](README.md#실제-사후-검수-구조와-운영-영향)을 따른다.
메시지/Outbox 저장 후 Kafka 분석 결과를 DB에 남기며, AI 결과로 메시지 전송이나 회원 제재를 자동 결정하지 않는다.
관리자 회원별 조회는 FLAGGED를 대상으로 MEDIUM/HIGH를 검토 건수로 세고,3건 이상에 REVIEW_REQUIRED 신호를 준다.
신고 생성은 AI 결과와 독립적이며, 관리자 신고 상세는 주변 메시지의 SAFE 결과도 보여줄 수 있다.
관리자 review는 위반 여부/검토자/시각을 기록하지만 회원 제재 상태를 변경하지 않는다.
따라서 Human 최종 판단을 지원하는 구조와 미확인 제재 집행을 혼동하지 않는다.

| 오류 | 기존 평가 — 변경 없음 | 현재 서비스 영향 |
|---|---|---|
| HIGH→MEDIUM + FLAGGED/Category 유지 | Risk Exact/Critical 기준 미달, HOLD 보존 | FLAGGED 근거와 검토 건수·3건 신호는 유지. 관리자에게 제공한 위험도 정보는 부정확 |
| FLAGGED→SAFE | FN/Review Target 누락, Critical이면 보호 실패 | AI 회원 집계/근거에서 빠져 검토 누락 가능. 사용자 신고가 보완할 수 있으나 모든 FN을 방지하지 않음 |
| SAFE→FLAGGED | FP 및 Native 대비 새 FP 보존 | 불필요한 근거/검토 부담. MEDIUM/HIGH면 검토 건수도 증가. 자동 제재 오류와 동일시하지 않음 |
| Category/Risk 기타 불일치 | 각각의 exact·하향 지표 유지 | 검토 사유/위험도 정보 부정확. LOW는 FLAGGED여도 검토 건수 제외 |

관리자 검토 대상 신호가 있다는 사실은 실제 관리자가 반드시 검토했다는 증거가 아니다.
실험의 Critical 실패53관측을 검토 누락53관측으로 바꾸지 않는다. 기존 Review Target 누락13 및
FN13은 그대로이며 HIGH→MEDIUM과 다른 운영 위험으로 다룬다.
반대로 사후 검수 구조라는 이유로 Critical 기준·GT를 완화하거나 FN을 수용 완료로 처리하지 않는다.

### 서비스 영향 해석의 무과금 확인

Production 코드를 변경하지 않고 기존 관리자/신고 테스트3클래스를 재실행했다.
`Issue68ReviewSignalIntegrationTest`4건(MySQL8.4 실제 JPA/Query), `AdminModerationReportServiceTest`4건,
`ChatRoomMemberReportServiceTest`4건: **총12 PASS / skip0 / 실패0**, Gradle test SUCCESS(15s).
HIGH와MEDIUM의검토3건 경계 동일성, SAFE/LOW 누락, AI 상태와독립적인신고 및 관리자 판단 기록을 확인했다.
이는 운영상의 실제 검토/제재 수행률이나 FN 수용 승인을 입증하지 않는다. 기존 Raw/GT/Gate 점수는 불변이다.
이번 해석 보완의 보존 검사에서는 기존2,034파일 중 README와 이 문서만 변경됐으며,
JSON/Raw/private journal·UNKNOWN/Production/Grafana 내용 변경0을 확인했다.
동결 소스77개와 기존 장부 SHA도 일치한다. 현재 브랜치에 연결된 PR은 검색되지 않아
저장소 template에 맞춘 로컬 본문 초안 `build/issue68/pr-body.md`만 준비했다.
Commit/Push/PR 생성·Merge/Issue Close 및 추가 유료 호출은 하지 않았다.

```sh
ruby -e 'ENV.keys.grep(/\A(?:OPENAI_API_KEY|ISSUE68_.*)\z/).each{|k|ENV.delete(k)};exec "./gradlew", ":test", "--tests", "*Issue68ReviewSignalIntegrationTest", "--tests", "*AdminModerationReportServiceTest", "--tests", "*ChatRoomMemberReportServiceTest", "--no-daemon", "--console=plain"'
```

## 판단 및 남은 작업

이번 표본에서는 모든 Batch 계열이 Native보다 적은 Token/추정 비용을 사용했고,
Size/Time50은 가장 적은12논리 요청과 Total14,624를 관측했다.
그러나 Poll 대비 추가 이득은14→12호출/Total16,269→14,624이며,
1회씩의 구성 차이와 모델 변동이 섞여 Size/Time50이 본질적으로 우월하다고 결론 내릴 수 없다.
Poll은 구조가 단순하고 Native 대비 Total Token75.8%, cache 할인 반영 예상 비용71.0% 감소를 관측했다.
한편 Native FN0 vs Poll FN1, Poll의 새 FP3/Critical9를 보존한다.
**실험상 품질 기준 미달과 서비스 운영상 수용 가능성은 서로 다른 판단**이다.
HIGH→MEDIUM의 검토 신호 유지와 FN의 누락 위험을 구분하되, 이번 표본으로 품질 유지·운영 수용이 입증되지는 않았다.

이에 **Kafka Poll-Batch를 우선 도입 검토 후보로 유지**한다. 현재 운영 채택/활성화 완료는 아니다.
기존 Gate V1/V2 HOLD와 기본 OFF를 유지하고 신규 FN 및 Critical 보호 문제의 추가 검증 후 Human이 활성화 여부를 결정한다.
Size/Time50의 더 큰 Token 절감도 FN3관측과 복잡성·동기 Buffer 대기 한계를 이유로 우선순위를 자동 바꾸지 않는다.

후속 개선은 아직 구현되지 않은 계획이다.

1. 관리자 검토 결정과 실제 제재 집행 결과가 확보되면 비식별·표본 편향 통제·Human 재심을 거쳐 품질 평가에 활용한다.
   현재 구현된 신고 위반 여부 기록을 Risk GT나 실제 제재 결과로 자동 간주하지 않는다.
2. 사용자 신고 이력과 AI 결과를 비교해 SAFE였던 신고/확인 사례를 미탐 후보로 분석한다.
   신고가 없었다고 정상 GT로 간주하지 않으며, 모든 FN이 신고된다고 가정하지 않는다.
3. 고위험 의심 메시지의 별도 검증/재분류 경로를 검토한다. 선별 기준·개인정보·추가 호출 비용·중복·운영 정책은
   Human 승인 대상이다. 현재 그러한 경로가 구현됐거나 FN을 해결한다고 주장하지 않는다.
4. HIGH/MEDIUM 경계는 신규 Human GT를 독립적으로 동결한 뒤 Control 포함 검증한다. 기존 GT/Prompt/Gate와 과거 점수는 보존한다.
5. 새 FN 회귀와 Critical 보호, Review Target3건 경계 및 허용 가능한 실제 지연을 확인한 뒤 Poll-Batch 운영 활성화를 재판단한다.

후속 검증/개선의 별도 범위와 예산은 Human 결정 사항이며 이 PR의 품질 PASS 전제나 자동 유료 실행 권한이 아니다.
추가 Prompt/A1/유료 호출, Commit/Push/PR 생성, #68/#55 Close는 하지 않았다.

완료 점검: `git diff --check` PASS, 신규 JSON syntax PASS, Evidence 상대 링크8개 정상,
Raw Usage/품질 합계 및 개별 timestamp p50/p95 재계산 일치.
새 결과 파일의 Key/Bearer/원문 필드 패턴 검사 통과. Private journal/claim은 Git ignore 유지.
실제 추가 파일은 Raw3개 + 집계JSON1개 + 이 문서1개이며 소스코드는 수정하지 않았다.
