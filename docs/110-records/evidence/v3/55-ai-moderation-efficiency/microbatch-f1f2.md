# Issue #55 — Batch 5 F1/F2 교차 진단

## 목적 / 측정 계약

- Primary: Batch 5에서 관측한 FN·FP·HIGH 위험도 미달이 입력 순서와 동반 메시지 한 건의 교체에 민감한지 확인한다.
- Secondary: 동일 요청 반복 변동, Provider Usage/Cache, HTTP Attempt/Retry, latency, 공개 단가 Estimated Cost.
- 안전 확인: Schema/ID 일대일 매핑/기존 Validator/잘림/UNRESOLVED, 요청 본문 동일성, 누적 $0.50 예산.
- Production 적용 또는 최적 Batch 크기를 결정하는 실험이 아니다. 품질 회귀를 허용하는 개선을 채택하지 않는다.
- Human 요청대로 C0 27회 완료·계약·비용 gate 점검 후 C1 27회를 별도 실행했다. Batch 10/20, Native, A/B/D 등의 유료 재실행은 하지 않았다.

## 기준 코드 / 고정 조건

- Branch: feature/55-ai-moderation-efficiency.
- HEAD: 339423fbca943d578bf07793ff336490904290eb + uncommitted test source. 이번 실험의 세 소스 파일 SHA-256은 각 JSON의 sourceHashes에 기록했다.
- Model alias: gpt-4o-mini. 실제 응답 54개 모두 gpt-4o-mini-2024-07-18.
- Prompt: moderation-prompt-v3-short-fragment-boundary. Policy: moderation-policy-v2.
- Production System Prompt / Few-shot 15개 / Rule / Validator / 단건 Schema / Batch 지시문 및 Batch Schema는 변경하지 않았다.
- Sampling과 Provider seed는 기존과 같이 별도 지정하지 않았다. Seed 55는 테스트 실행 계획용이며 Provider 결정성을 보장하지 않는다.
- Batch 5 / max_tokens=640 / SDK maxRetries=3 / timeout=90초. 기존 Batch 평가 경로를 재사용한다.
- 원본 F0 진단 20건의 메시지·GT·중립 ID를 재사용하되 이번 호출은 중점 세 Case의 세 그룹만 사용했다. C0 고유 Case 15개, C1 13개, 두 구성의 합집합 15개다. Held-out 80/진단 20 전체의 재평가가 아니다.
- 중점 Case: PROFANITY-16, PROFANITY-15는 FLAGGED/[PROFANITY]/HIGH, SAFE-17은 SAFE/[]/LOW가 Human GT다.
- Provider에는 중립 ID와 기존 평가 메시지만 보냈다. GT/Case ID는 보내지 않았다. 이 Evidence에는 메시지 원문, 인증 header, API Key, 실제 서비스 식별자를 저장하지 않았다.

## 구성 / 순서 계약

| 대상 | C0 기존 입력 순서 | C1 한 건 교체 | O0/O1 대상 위치 | O2 위치 |
|---|---|---|---:|---:|
| PROFANITY-16 | SPAM-08, SAFE-09, PROFANITY-16, PROFANITY-01, PROFANITY-06 | PROFANITY-01 → PROFANITY-14 | 3 | 1 |
| PROFANITY-15 | PROFANITY-13, SAFE-01, PROFANITY-17, SPAM-15, PROFANITY-15 | PROFANITY-17 → PROFANITY-14 | 5 | 1 |
| SAFE-17 | SAFE-17, PI-05, PROFANITY-14, SPAM-16, SPAM-19 | PROFANITY-14 → PROFANITY-13 | 1 | 5 |

- O0: 기존 순서.
- O1: 대상 위치는 고정하고 다른 네 항목만 역순.
- O2: 대상만 반대쪽 끝으로 이동하며 다른 네 항목의 상대 순서는 유지.
- C0/C1 비교에서는 같은 O 조건의 대상 위치와 나머지 세 동반 항목이 같고 한 항목만 다르다.
- 대상 3 × 구성 2 × 순서 3 × 반복 3 = 54 논리 호출. 중점 판정 54개, 전체 반환 항목 270개. 반복된 관측이며 독립 신규 Dataset 270건이 아니다.
- 각 구성 안에서는 순서 조건/중점 Case의 실행을 교차했다. 실제 전체 schedule과 시각은 JSON에 있다. C0와 C1 자체는 교차하지 않았다.

## 실제 실행 / C0 gate

- C0: 2026-10-20T08:25:40.858834Z ~ 2026-10-20T08:26:36.937350Z.
- C1: 2026-10-20T08:27:08.203808Z ~ 2026-10-20T08:28:01.986478Z.
- C0: 27 호출/27 HTTP 200/Retry 0/응답 계약 오류 0/Usage 정상. 9개 셀의 세 반복 wire body가 모두 동일함을 별도 Python 검산 후 C1을 실행했다.
- C1 시작 시 C0의 완료 상태·27개 결과·Model·Prompt/Schema·실험 소스 해시와 누적 예산을 다시 확인했다.
- 완료 후 양 단계의 manifest, Prompt/Schema/sourceHashes가 동일하고 Java 결과와 독립 Python 계산이 일치함을 확인했다.
- 전체 18개 셀 각각의 세 반복에서 semantic request specification SHA-256 및 실제 HTTP body SHA-256이 하나씩만 존재했다. HTTP header까지 동일하다는 뜻은 아니다.
- 실제 요청의 System/User/Model/cap/strict Schema/미지정 Sampling도 메모리에서 검증했다. raw 요청 본문은 저장하지 않았다.
- C0/O0 세 그룹의 semantic request specification SHA-256은 기존 B의 해당 Batch 5 요청과 정확히 같았다. 과거 B는 실제 HTTP body hash를 저장하지 않았으므로 시점 간 wire byte 동일성까지 주장하지 않는다.
- 모든 요청에서 누락·중복·미요청 ID·개수 불일치·Parse·Validation·잘림·Provider failure·UNRESOLVED는 0이었다.
- 실제 응답의 중점 ID 위치는 54회 모두 입력 위치와 같았다. 앱 매핑은 배열 순서가 아니라 ID로 수행하며, 역순 응답은 별도 Fake 테스트로 검증했다.

## 중점 Case의 조건별 3회 원본 판정

S = SAFE/[]/LOW, P/M = FLAGGED/[PROFANITY]/MEDIUM, P/H = FLAGGED/[PROFANITY]/HIGH.
아래 약어의 enum 원값과 모든 동반 Case의 판정은 연결된 JSON calls[].response.cases[]에 그대로 보존했다.

| Case | 순서 | C0 R1 / R2 / R3 | C1 R1 / R2 / R3 |
|---|---|---|---|
| PROFANITY-16 | O0 | S / S / S | P/M / P/M / P/M |
| PROFANITY-16 | O1 | P/M / P/M / P/M | P/M / P/M / P/M |
| PROFANITY-16 | O2 | P/M / P/M / P/M | P/M / P/M / P/M |
| PROFANITY-15 | O0 | P/M / P/M / P/M | P/M / P/H / P/M |
| PROFANITY-15 | O1 | P/M / P/H / P/H | P/M / P/H / P/H |
| PROFANITY-15 | O2 | P/M / P/M / P/H | P/M / P/H / P/H |
| SAFE-17 | O0 | P/M / P/M / P/M | P/M / P/M / P/M |
| SAFE-17 | O1 | P/M / P/M / P/M | P/M / P/M / P/M |
| SAFE-17 | O2 | P/M / P/M / P/M | P/M / P/M / S |

### 동일 요청 반복 변동

| 구성 | 대상 | 순서 | 3회 판정 |
|---|---|---|---|
| C0 | PROFANITY-15 | O1 | P/M / P/H / P/H |
| C0 | PROFANITY-15 | O2 | P/M / P/M / P/H |
| C1 | PROFANITY-15 | O0 | P/M / P/H / P/M |
| C1 | PROFANITY-15 | O1 | P/M / P/H / P/H |
| C1 | PROFANITY-15 | O2 | P/M / P/H / P/H |
| C1 | SAFE-17 | O2 | P/M / P/M / S |

- 중점 Case 기준 6/18개 셀에서 반복 변동, 12/18개 셀에서 세 반복 tuple 일치. 셀 내부 요청 본문은 변하지 않았다.
- 동일 요청 변동을 관측했지만 그 내부 원인을 sampling만으로 확정하지 않는다. Provider backend/시점 등은 통제하지 못했다.

### 순서만 변경했을 때

- PROFANITY-16 C0: O0는 S/S/S, O1·O2는 모두 P/M/P/M/P/M. 특히 O1은 대상 위치가 3번으로 같은데도 달라졌다. 절대 위치만으로 결과가 정해진다는 설명은 이 관측을 설명하지 못한다.
- PROFANITY-16 C1: 모든 순서에서 P/M 3/3. C0에서 보인 순서 민감성이 C1에서는 관측되지 않아 구성에 따라 순서 민감성이 달라질 가능성을 지지한다.
- PROFANITY-15: C0 O0의 HIGH는 0/3, O1은 2/3, O2는 1/3. C1에서는 각각 1/3, 2/3, 2/3. 일부 순서 차이와 같은 셀의 반복 변동이 공존하므로 순서 하나로 위험도 변화 원인을 확정할 수 없다.
- C0 PROFANITY-15 R2는 O1=HIGH, O2=MEDIUM이었다. O0 기준 비교만 하면 놓칠 수 있는 HIGH 하향이다.
- SAFE-17: C0 모든 순서에서 FP 3/3, C1 O0/O1도 FP 3/3, O2만 P/M/P/M/S. 한 번의 SAFE를 안정적 개선으로 해석하지 않는다.

### 동반 메시지 하나만 교체했을 때

- PROFANITY-16 O0: C0 S/S/S → C1 P/M/P/M/P/M. FN은 줄었지만 세 번 모두 GT HIGH에 미달한다. O1/O2는 두 구성 모두 P/M이다.
- PROFANITY-15: HIGH 횟수는 O0 0→1, O1 2→2, O2 1→2(각 /3). 같은 셀에서도 변동하므로 구성 효과와 실행 변동의 기여를 분리해 확정하기 어렵다.
- SAFE-17: O0/O1 FP는 유지되고 O2에서만 FP 3/3→2/3. 강한 위협 동반 문장 제거가 FP를 일관되게 제거하지 못했다.
- C0/C1은 시간 block이 다르므로 구성 효과의 인과적 확정은 제한된다. 같은 R번호도 동일한 Provider 난수나 완전히 paired된 실행 환경을 뜻하지 않는다.

## 안전성 / 새로운 오류

| 중점 Case 판정만 집계 | C0, 27판정 | C1, 27판정 |
|---|---:|---:|
| GT 대비 FN | 3 | 0 |
| GT 대비 FP | 9 | 8 |
| GT HIGH 미달, SAFE/LOW 포함 | 15/18 | 13/18 |
| Result/Category/Risk 모두 GT 일치 | 3 | 6 |

위 집계는 사후 선정한 세 대상의 반복 관측이다. 전체 Held-out 품질이나 C1의 일반적 품질 개선율이 아니다.

### 동반 Case도 포함한 확인

- C1의 중점 FN이 없어졌다는 사실만으로 안전하다고 판단하지 않는다.
- **새로 추가한 PROFANITY-14의 FN 3회**: PROFANITY-15 그룹 C1/O0/R2, C1/O1/R2·R3에서 SAFE. 이 Case는 해당 C0 그룹에 없었으므로 같은 Case의 paired new FN이라고 세지 않고, 새로 포함한 항목의 GT FN으로 구분한다.
- **공통 Case의 새 FP 3회**: PROFANITY-06이 PROFANITY-16 그룹에서 C0 SAFE → C1 P/M. 위치는 O0/R1, O2/R1·R3.
- 공통 Case를 같은 대상/순서/반복 번호로 비교한 C0→C1에서 new FN 0, FN 개선 3(PROFANITY-16 O0), new FP 3(PROFANITY-06), FP 개선 1(SAFE-17 O2/R3). 이는 시간 block 차이가 남는 비교다.
- **Prompt 금지 조합 4회**: PROFANITY-13이 FLAGGED/[PROFANITY]/LOW. PROFANITY-15 그룹 C0/O0/R1·R2, C1/O0/R2·R3. 기존 Validator가 허용한 조합이므로 Validation failure 0과 모순되지 않는다. Validator는 변경하지 않았다.
- 전체 반환 135항목씩의 GT FN은 C0 3회(PROFANITY-16), C1 3회(PROFANITY-14). FP는 C0 27회, C1 38회. 구성 변경으로 대상 Case/등장 횟수가 다르므로 이 합계를 같은 Dataset 정확도 비교로 사용하지 않는다.

## Token / Latency / Estimated Cost

- 공개 단가: 1M Token당 Input $0.15 / Cached Input $0.075 / Output $0.60. [OpenAI 공식 단가](https://developers.openai.com/api/docs/models/gpt-4o-mini). OpenAI Batch API 할인이 아닌 일반 동기 요청이다.
- 예산 장부의 prior는 기존 B까지의 보수적 추정 $0.11129570. 과거 일부 실험은 HTTP/Cache 정보가 부족하므로 실제 청구액 증명이 아니다.
- 사전 O200K_BASE 추정은 System/User/Schema + 요청당 256 Token 여유 + 전체 Output cap을 사용했다. C0 $0.01638495 / C1 $0.01638360, 합 $0.03276855. 네 Attempt 가정 합 $0.13107420, prior 포함 $0.24236990.
- 실제 호출 전 매 HTTP Attempt마다 128,000 Input + cap 640의 미할인 비용을 예약했다. 정상 Usage가 있으면 미할인 값으로 정산하고 없으면 예약액을 유지하도록 기존 보호 방식을 재사용했다.
- Cache 할인은 예산 보호에서 차감하지 않았다. 실패 응답·알 수 없는 Usage가 있었다면 그 한계를 별도 기록해야 하지만 이번 54개 응답은 모두 정상 Usage를 제공했다.

| 구성 | 논리/HTTP/Retry | Input | Output | Cached Input | Total | 미할인 추정 USD | Cache 반영 추정 USD | latency avg/p95/p99 ms |
|---|---|---:|---:|---:|---:|---:|---:|---|
| C0 | 27/27/0 | 28341 | 3483 | 12288 | 31824 | $0.00634095 | $0.00541935 | 1937.1 / 2680.1 / 3110.7 |
| C1 | 27/27/0 | 28332 | 3556 | 12288 | 31888 | $0.00638340 | $0.00546180 | 1858.6 / 2287.9 / 2561.2 |

- 합계: Input 56,673 / Output 7,039 / Cached Input 24,576 / Total 63,712. Cached Input은 Input의 부분집합이다.
- 이번 54회의 미할인 Estimated Cost $0.01272435 / Cache 반영 $0.01088115. 최종 누적 보수적 추정 $0.12402005 < $0.50.
- Batch Usage는 요청별 한 번만 기록했다. Case에 복제/임의 배분하지 않았다. 아래 비용은 실제 Billing이 아닌 공개 단가 추정이다.
- 논리 호출 latency 평균 1,897.9ms / p95 2,561.2ms / p99 3,110.7ms. percentile은 nearest-rank. 소표본/Cache/시점/네트워크가 섞여 있어 운영 latency나 효율 개선율로 일반화하지 않는다.
- 단계 wall time C0 약 56.079초 / C1 약 53.783초. 두 단계 사이 약 31.266초의 검산/Gradle 시작 간격이 있다. 최초 시작~최종 종료 약 141.128초는 이 간격도 포함한다.
- Kafka 대기, batch maxWait, DB 저장, 실제 traffic E2E latency는 측정하지 않았다.

### 요청별 실제 Usage / Latency

순서는 각 단계의 실제 호출 순서다. I/O/C/T는 Input/Output/Cached/Total이며 latency는 논리 ChatClient 호출 구간이다.

| 구성/번호 | 대상/순서/반복 | I / O / C / T | latency ms | HTTP | 미할인 USD | Cache 반영 USD |
|---|---|---|---:|---:|---:|---:|
| C0/01 | PROFANITY-16/O0/R1 | 1044 / 120 / 0 / 1164 | 3110.7 | 1 | $0.00022860 | $0.00022860 |
| C0/02 | SAFE-17/O1/R1 | 1058 / 134 / 0 / 1192 | 2268.8 | 1 | $0.00023910 | $0.00023910 |
| C0/03 | PROFANITY-15/O2/R1 | 1047 / 128 / 0 / 1175 | 1877.3 | 1 | $0.00023385 | $0.00023385 |
| C0/04 | PROFANITY-16/O1/R1 | 1044 / 128 / 0 / 1172 | 1626.7 | 1 | $0.00023340 | $0.00023340 |
| C0/05 | SAFE-17/O2/R1 | 1058 / 134 / 0 / 1192 | 1818.1 | 1 | $0.00023910 | $0.00023910 |
| C0/06 | PROFANITY-15/O0/R1 | 1047 / 127 / 0 / 1174 | 1851.5 | 1 | $0.00023325 | $0.00023325 |
| C0/07 | PROFANITY-16/O2/R1 | 1044 / 128 / 0 / 1172 | 2441.9 | 1 | $0.00023340 | $0.00023340 |
| C0/08 | SAFE-17/O0/R1 | 1058 / 134 / 0 / 1192 | 1713.4 | 1 | $0.00023910 | $0.00023910 |
| C0/09 | PROFANITY-15/O1/R1 | 1047 / 128 / 0 / 1175 | 1831.2 | 1 | $0.00023385 | $0.00023385 |
| C0/10 | SAFE-17/O1/R2 | 1058 / 134 / 1024 / 1192 | 1938.3 | 1 | $0.00023910 | $0.00016230 |
| C0/11 | PROFANITY-16/O2/R2 | 1044 / 128 / 0 / 1172 | 1628.2 | 1 | $0.00023340 | $0.00023340 |
| C0/12 | PROFANITY-15/O0/R2 | 1047 / 127 / 1024 / 1174 | 2680.1 | 1 | $0.00023325 | $0.00015645 |
| C0/13 | SAFE-17/O2/R2 | 1058 / 134 / 1024 / 1192 | 1999.7 | 1 | $0.00023910 | $0.00016230 |
| C0/14 | PROFANITY-16/O0/R2 | 1044 / 121 / 0 / 1165 | 2198.6 | 1 | $0.00022920 | $0.00022920 |
| C0/15 | PROFANITY-15/O1/R2 | 1047 / 127 / 1024 / 1174 | 1889.5 | 1 | $0.00023325 | $0.00015645 |
| C0/16 | SAFE-17/O0/R2 | 1058 / 134 / 1024 / 1192 | 1685.1 | 1 | $0.00023910 | $0.00016230 |
| C0/17 | PROFANITY-16/O1/R2 | 1044 / 128 / 0 / 1172 | 2255.9 | 1 | $0.00023340 | $0.00023340 |
| C0/18 | PROFANITY-15/O2/R2 | 1047 / 128 / 1024 / 1175 | 1814.0 | 1 | $0.00023385 | $0.00015705 |
| C0/19 | PROFANITY-16/O2/R3 | 1044 / 128 / 0 / 1172 | 1763.5 | 1 | $0.00023340 | $0.00023340 |
| C0/20 | SAFE-17/O0/R3 | 1058 / 134 / 1024 / 1192 | 1840.6 | 1 | $0.00023910 | $0.00016230 |
| C0/21 | PROFANITY-15/O1/R3 | 1047 / 127 / 1024 / 1174 | 1661.4 | 1 | $0.00023325 | $0.00015645 |
| C0/22 | PROFANITY-16/O0/R3 | 1044 / 121 / 0 / 1165 | 1580.7 | 1 | $0.00022920 | $0.00022920 |
| C0/23 | SAFE-17/O1/R3 | 1058 / 134 / 1024 / 1192 | 1832.4 | 1 | $0.00023910 | $0.00016230 |
| C0/24 | PROFANITY-15/O2/R3 | 1047 / 127 / 1024 / 1174 | 1802.4 | 1 | $0.00023325 | $0.00015645 |
| C0/25 | PROFANITY-16/O1/R3 | 1044 / 128 / 0 / 1172 | 1625.4 | 1 | $0.00023340 | $0.00023340 |
| C0/26 | SAFE-17/O2/R3 | 1058 / 134 / 1024 / 1192 | 1610.8 | 1 | $0.00023910 | $0.00016230 |
| C0/27 | PROFANITY-15/O0/R3 | 1047 / 128 / 1024 / 1175 | 1955.0 | 1 | $0.00023385 | $0.00015705 |
| C1/01 | PROFANITY-16/O0/R1 | 1043 / 134 / 0 / 1177 | 2561.2 | 1 | $0.00023685 | $0.00023685 |
| C1/02 | SAFE-17/O1/R1 | 1058 / 134 / 0 / 1192 | 1826.1 | 1 | $0.00023910 | $0.00023910 |
| C1/03 | PROFANITY-15/O2/R1 | 1047 / 135 / 0 / 1182 | 2230.5 | 1 | $0.00023805 | $0.00023805 |
| C1/04 | PROFANITY-16/O1/R1 | 1043 / 128 / 0 / 1171 | 1878.2 | 1 | $0.00023325 | $0.00023325 |
| C1/05 | SAFE-17/O2/R1 | 1058 / 134 / 0 / 1192 | 1943.2 | 1 | $0.00023910 | $0.00023910 |
| C1/06 | PROFANITY-15/O0/R1 | 1047 / 135 / 0 / 1182 | 1720.3 | 1 | $0.00023805 | $0.00023805 |
| C1/07 | PROFANITY-16/O2/R1 | 1043 / 135 / 0 / 1178 | 2026.3 | 1 | $0.00023745 | $0.00023745 |
| C1/08 | SAFE-17/O0/R1 | 1058 / 134 / 0 / 1192 | 1827.5 | 1 | $0.00023910 | $0.00023910 |
| C1/09 | PROFANITY-15/O1/R1 | 1047 / 135 / 0 / 1182 | 1803.1 | 1 | $0.00023805 | $0.00023805 |
| C1/10 | SAFE-17/O1/R2 | 1058 / 134 / 1024 / 1192 | 1965.9 | 1 | $0.00023910 | $0.00016230 |
| C1/11 | PROFANITY-16/O2/R2 | 1043 / 128 / 0 / 1171 | 2287.9 | 1 | $0.00023325 | $0.00023325 |
| C1/12 | PROFANITY-15/O0/R2 | 1047 / 126 / 1024 / 1173 | 1520.3 | 1 | $0.00023265 | $0.00015585 |
| C1/13 | SAFE-17/O2/R2 | 1058 / 134 / 1024 / 1192 | 2058.3 | 1 | $0.00023910 | $0.00016230 |
| C1/14 | PROFANITY-16/O0/R2 | 1043 / 128 / 0 / 1171 | 1952.4 | 1 | $0.00023325 | $0.00023325 |
| C1/15 | PROFANITY-15/O1/R2 | 1047 / 127 / 1024 / 1174 | 1818.9 | 1 | $0.00023325 | $0.00015645 |
| C1/16 | SAFE-17/O0/R2 | 1058 / 134 / 1024 / 1192 | 1929.0 | 1 | $0.00023910 | $0.00016230 |
| C1/17 | PROFANITY-16/O1/R2 | 1043 / 128 / 0 / 1171 | 1558.9 | 1 | $0.00023325 | $0.00023325 |
| C1/18 | PROFANITY-15/O2/R2 | 1047 / 133 / 1024 / 1180 | 1819.7 | 1 | $0.00023685 | $0.00016005 |
| C1/19 | PROFANITY-16/O2/R3 | 1043 / 135 / 0 / 1178 | 1732.0 | 1 | $0.00023745 | $0.00023745 |
| C1/20 | SAFE-17/O0/R3 | 1058 / 134 / 1024 / 1192 | 1748.9 | 1 | $0.00023910 | $0.00016230 |
| C1/21 | PROFANITY-15/O1/R3 | 1047 / 127 / 1024 / 1174 | 1666.7 | 1 | $0.00023325 | $0.00015645 |
| C1/22 | PROFANITY-16/O0/R3 | 1043 / 128 / 0 / 1171 | 1833.3 | 1 | $0.00023325 | $0.00023325 |
| C1/23 | SAFE-17/O1/R3 | 1058 / 134 / 1024 / 1192 | 1564.1 | 1 | $0.00023910 | $0.00016230 |
| C1/24 | PROFANITY-15/O2/R3 | 1047 / 133 / 1024 / 1180 | 1717.4 | 1 | $0.00023685 | $0.00016005 |
| C1/25 | PROFANITY-16/O1/R3 | 1043 / 128 / 0 / 1171 | 1746.4 | 1 | $0.00023325 | $0.00023325 |
| C1/26 | SAFE-17/O2/R3 | 1058 / 127 / 1024 / 1185 | 1633.1 | 1 | $0.00023490 | $0.00015810 |
| C1/27 | PROFANITY-15/O0/R3 | 1047 / 134 / 1024 / 1181 | 1813.6 | 1 | $0.00023745 | $0.00016065 |

## 지지된 가설 / 확정할 수 없는 원인

| 가설 | 판정 / 근거 |
|---|---|
| Batch 크기만이 판정을 결정한다 | 크기를 5로 고정해도 PROFANITY-16의 판정이 달라져 단독 설명으로 불충분 |
| 대상의 절대 배열 위치만 문제다 | C0 O0/O1은 대상 3번 위치가 같은데 S 3/3과 P/M 3/3으로 달라져 단독 설명으로 불충분 |
| 동반 순서가 판정에 영향을 줄 수 있다 | PROFANITY-16 C0 O0/O1 차이가 이를 지지. 내부 Attention/추론 메커니즘이나 통계적 인과 확정은 아님 |
| 동반 항목의 교체에 민감할 수 있다 | PROFANITY-16 O0와 동반 PROFANITY-06의 변화가 지지하지만 C0/C1 실행 시점 차이가 남음 |
| 동일 요청이면 항상 같은 답이다 | PROFANITY-15/SAFE-17의 동일 wire body 반복 변동이 반례 |
| 위협 동반 메시지만 제거하면 SAFE-17 FP가 해결된다 | C1 O0/O1 FP 3/3, O2도 2/3이므로 이 교체로 일관 해결되지 않음 |
| ID 매핑 오류가 관측 차이를 만들었다 | 이번 요청의 일대일 ID 검증 통과 및 Fake 역순 매핑 통과. 앱 매핑 오류의 증거는 없지만 Provider의 의미 판단 정확성을 증명한 것은 아님 |

- O2는 위치와 인접 관계/출력 순서를 함께 바꾸므로 순수 위치 효과만 분리하지 못한다.
- F2는 한 메시지의 의미·길이·GT 강도를 함께 교체한다. 배열 위치 고정과 절대 Token offset 고정은 다르다. 대상이 첫 번째인 대조도 포함했으나 본 실험에서 모든 영향 요소를 분리한 것은 아니다.
- 원래 동반 항목의 효과와 대체 항목의 효과, 구체적인 단어/강도/문맥 혼입 메커니즘은 미확정이다.
- Batch 입력 JSON/추가 지시문/출력 Schema 중 어느 것이 Native와 차이를 만드는지는 이번 실험에서 분해하지 않았다.
- 3회 반복, 사후 선정한 소수 Case, 하나의 교체안만 사용했다. Production/전체 Held-out의 오류율이나 최적 Batch 크기를 확정할 수 없다.
- 특정 셀에서 정답으로 바뀌었다고 원인을 확정하지 않는다. FN·HIGH 미달·FP·Prompt 위반이 남아 있어 Production 적용 근거로 사용하지 않는다.

## 검증 / 실행 명령

아래 유료 테스트는 .env 전체를 source하지 않고 OPENAI_API_KEY 한 항목만 메모리에서 읽어 Gradle 자식 환경에 전달했다. 값은 명령 인수/로그/Evidence에 출력하지 않았으며 --no-daemon을 사용했다.
자식 Key 존재 확인 PASS / Provider 호출 0, C0 실제 평가 1 test PASS / 27 calls, C1 실제 평가 1 test PASS / 27 calls.
유료 테스트 PASS는 요청/실행 계약 통과이며 품질 Guardrail PASS가 아니다.

```bash
./gradlew --no-daemon :test --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossOpenAiTest.자식프로세스의_Key_존재만_확인하며_Provider를_호출하지_않는다' -PshowTestOutput --console=plain
./gradlew --no-daemon :test --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossOpenAiTest.C0_고정구성_9조건을_3회씩_27호출한다' -PshowTestOutput --console=plain
./gradlew --no-daemon :test --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossOpenAiTest.C0_통과후에만_C1_교체구성_27호출한다' -PshowTestOutput --console=plain

env -u OPENAI_API_KEY ./gradlew :test \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosisTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchSizeComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchF0Test' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparisonTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluationTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationHeldoutDatasetTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetricsTest' \
  --tests 'com.bobfull.chat.infrastructure.ai.ModerationPromptTest' --console=plain

env -u OPENAI_API_KEY ./gradlew build -x test --console=plain
```

- 신규 무과금 계약 테스트 6 PASS: C0/C1 한 건 교체·대상 위치·ID/GT 보존, O1/O2 순서 계약, 27+27 계획 재현, 예산 추정, Fake 54회 역순 응답/실제 wire 반복 동일성/Usage 중복 방지, 비정상 wire 거절.
- 최종 관련 무과금 회귀 92 tests / skipped 0 / failures 0 / errors 0. Fake Provider는 로컬 loopback HTTP이며 실제 API Key를 사용하지 않았다.
- compileJava / compileTestJava PASS. root + lambda build -x test SUCCESS. 전체 test suite는 이번에 실행하지 않았다.
- 최초 sandbox 내 Gradle 실행은 cache lock 접근 권한으로 시작하지 못했다. 허용된 실행 권한으로 재실행했고 위 결과를 확인했다. 이는 테스트 또는 Provider 실패가 아니다.
- 기존 29개 미커밋 파일의 SHA-256 보존, tracked/staged diff 없음, 신규 JSON syntax와 git diff --check 및 신규 파일 whitespace 검사 확인.
- Production/Prompt/Validator/Rule/Policy/Schema/운영 설정/Kafka/DB 변경 없음. Commit/Push/PR/Issue 변경 없음.

## 기록 파일 / 다음 단계

- [C0 실제 결과 및 요청별 해시](./microbatch-f1f2-c0.json)
- [C1 실제 결과 및 요청별 해시](./microbatch-f1f2-c1.json)
- [B 크기 탐색](./microbatch-size-b.md), [F0 반복](./microbatch-f0.md), [A 형식 대조](./microbatch-format-a.md), [공식 Before/Prompt 실험](./README.md)은 그대로 보존했다.
- 새 테스트 전용 파일: Issue55MicroBatchCrossDiagnosis.java / Issue55MicroBatchCrossDiagnosisTest.java / Issue55MicroBatchCrossOpenAiTest.java.
- Batch 10 재현은 필요성과 별도 Human 요청이 있을 때만 진행한다. 이번에 추가 실행하거나 Production 설정을 바꾸지 않는다.
