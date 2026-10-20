# Issue #55 — Common Policy Anchoring Q1 진단 실험

## 목적 / 범위 / 측정 계약

Native와 Batch의 공통 Prompt 변경 효과와 요청 형식에 따른 차이를 구분한다.
Q1은 Human이 승인한 네 문장만 추가한 테스트 전용 후보다. Production 적용·품질 개선 확정이 목적은 아니다.

- Primary: A→B / C→D / B→D의 Case별 Result·Category·Risk, 새 FP/FN, GT HIGH 미달 및 새 HIGH 하향, 정책 위반 조합, 반복 안정성.
- Secondary: Provider Usage / Cached Input / 공개 단가 Estimated Cost / logical latency / HTTP Attempt / Retry.
- 안전 확인: 실제 요청 계약·반복 wire 동일성, strict Schema, ID 매핑, 기존 Validator, 잘림, 누락·중복, UNRESOLVED, 누적 $0.50 예산.
- **진단20건 × 네 조건 × 3회 = 240항목 판정, 132논리 호출**만 실행했다.
- 각 조건의 60관측은 같은 20건의 반복이지 독립 신규 60건이 아니다.
- 기존 80건 및 신규40건 Provider 평가, Q1 재튜닝, 추가 반복은 하지 않았다.
- 기존 Evidence / 미커밋 파일 / Production Prompt·Policy·Rule·Model·Validator·Kafka·DB·설정은 변경하지 않았다.

## 기준 코드 / 조건

- Branch: feature/55-ai-moderation-efficiency.
- HEAD: 339423fbca943d578bf07793ff336490904290eb + uncommitted test source.
- 세 실행 소스 SHA-256: raw JSON sourceHashes. 실제 호출 종료 후 소스 파일을 수정하지 않았다.
- Model alias: gpt-4o-mini. 모든 실제 응답: gpt-4o-mini-2024-07-18.
- 원문 Prompt: moderation-prompt-v3-short-fragment-boundary, Policy: moderation-policy-v2.
- raw JSON promptVersion은 원문 버전을 뜻한다. B/D는 별도 실험 Q1이며 candidateSystemSha256으로 구분한다. Production version을 변경한 것이 아니다.
- 원래 F0의 20건/GT/중립 ID/입력 순서/두 Batch 구성 그대로. 모두 Rule miss다. GT SAFE11 / FLAGGED9 / HIGH7.
- Native는 메시지 문자열, Batch는 기존 items(id,content). 출력도 각각 기존 ModerationResult / results(id,moderation).
- Output cap Native128 / Batch10 1280. Sampling/Provider seed 미지정, SDK maxRetries3 / timeout90s.
- Seed55는 기존 데이터·계획 기준이며 Provider 결정성을 뜻하지 않는다.
- Native 실험은 Production과 같은 System/User/Schema/cap을 사용하되 raw 응답 후 strict 변환으로 실패 Usage를 보존한다. Kafka/DB/전체 Production E2E는 포함하지 않는다.

| 조건 | 요청 형식 | 공통 Prompt | 반복당 호출 | 3회 호출 |
|---|---|---|---:|---:|
| A | Native | 기존 원문 | 20 | 60 |
| B | Native | Q1 | 20 | 60 |
| C | Batch10 | 기존 원문 + 기존 Batch 계약 | 2 | 6 |
| D | Batch10 | Q1 + 같은 기존 Batch 계약 | 2 | 6 |

Item-wise Policy Checklist Candidate는 사용하지 않았다.

## 승인된 Q1 동결

아래 각 anchor 바로 다음 줄에 sentence 하나씩만 추가한다. 기존 문장 삭제·교체 없음.
Few-shot15개와 Output rules는 원문 그대로 유지한다.

| 원문 anchor | 추가 문장 |
|---|---|
| LOW: 사용하지 않는다. 경미한 농담, 친근한 호칭, 문맥 의존 표현은 SAFE다. | 경미한 표현과 직접 모욕은 표면 단어가 아니라 문장 전체의 의미로 구분한다. |
| HIGH: 매우 강한 욕설, 심각한 모욕, 협박, 위협 또는 위해를 가하겠다는 표현이다. | 협박·위협에 해당하면 욕설 단어 유무와 무관하게 HIGH다. |
| 제외: 공개 사업장 연락처와 일반 숫자. | 연락처 형식뿐 아니라 메시지에 나타난 개인·공개 사업장 용도와 숫자의 역할을 구분한다. |
| 제외: 정상 추천·후기·메뉴·예약·지도 링크이며 링크 자체만으로 SPAM 처리하지 않는다. | 상업적 유인은 내용에 명시되어야 하며, 정상 안내만으로 추정하지 않는다. |

- 원문: 1576자 / 42줄 / O200K_BASE 추정720token.
- Q1: 1743자 / 46줄 / 같은 tokenizer 추정818token.
- 위 token은 로컬 추정이다. 실제 Provider 전체 input과 동일하지 않다.
- 원문 System SHA-256: `7540e67d11d342e9b9618204baa8d5868ebfd21a15716a8f41dc5fbcb35249a4`.
- Q1 System SHA-256: `d4f2d1b521ce2cf31102b004b96afc1f478e594b1e3dc37bdead19417e8f8af5`.
- 단건/Batch Schema와 원래 Batch System 해시는 raw JSON baseHashes.
- 테스트에서 네 문장을 제거한 결과가 원문과 완전히 같고, 기존 A/C wire와 새 Control wire가 동일함을 검증했다.
- B/D는 대응 Control에서 System 내용만 달라지며 다른 요청 필드가 동일함을 Fake로 검사했다.
- 실시간 interceptor도 System/User/Schema/Model/cap/미지정 sampling을 검사했다. 조건별 44개 셀 모두 세 반복의 실제 요청 본문 SHA-256이 같았다.

## 교차 실행 / 예산 / 계약 결과

각 반복에서 F0 1~10 / 11~20 두 그룹을 유지하고, 그룹마다 A/B/C/D 블록 선행 순서를 회전했다.
Native 블록은 그 그룹의 10개 단건, Batch 블록은 같은 10개를 한 번에 호출한다.

| 반복 / 그룹 | 조건 블록 순서 |
|---|---|
| R1/G1 | A → B → C → D |
| R1/G2 | B → C → D → A |
| R2/G1 | C → D → A → B |
| R2/G2 | D → A → B → C |
| R3/G1 | A → B → C → D |
| R3/G2 | B → C → D → A |

- 순차 호출이며 그룹·그룹 내 입력 순서는 바꾸지 않았다. 조건 블록을 회전했지만 6블록이라 선행 조건 횟수가 완전 균형은 아니고 시간/backend/Cache 영향도 완전히 제거하지 못했다.
- 실제 schedule/요청 시각은 JSON에 남겼다.
- 실행 2026-10-20T10:07:08.202051Z ~ 2026-10-20T10:09:50.432978Z.
- Key는 .env의 OPENAI_API_KEY 항목 하나만 Gradle 자식 환경에 일시 전달했다. source/eval/다른 dotenv 변수 주입/키 출력/명령 인수 기록은 하지 않았다.
- Key 존재 확인 PASS, Provider0회 후 실제 진단 실행.
- 실제 논리132 / HTTP132 / HTTP200 132 / Retry0.
- Provider·Parse·Mapping·Validation·잘림·불완전 응답·UNRESOLVED 모두0. 입력/반환 ID 누락·중복·미요청 ID·개수 불일치0.
- **실행 테스트 PASS는 계약 통과와 132회 완료이며 품질 Gate PASS가 아니다.**
- 메시지 원문/인증 header/API Key/실제 서비스 식별자는 결과 파일에 보존하지 않았다. Case ID·중립 ID·원문 SHA-256·예상/실제 enum·Usage·wire hash만 기록한다. controlSystem에 있는 Few-shot은 기존 합성 예시다.

### 사전 견적과 실제 누적

사전 O200K_BASE System/User/Schema + 요청당 framing 여유256 + 전체 Output cap 계획:

| 조건 | 호출 | 추정 Input | Output cap 합 | 미할인 계획 USD | 최대4 Attempt 계획 USD |
|---|---:|---:|---:|---:|---:|
| A | 60 | 70137 | 7680 | $0.01512855 | $0.06051420 |
| B | 60 | 76017 | 7680 | $0.01601055 | $0.06404220 |
| C | 6 | 9495 | 7680 | $0.00603225 | $0.02412900 |
| D | 6 | 10083 | 7680 | $0.00612045 | $0.02448180 |

- 사전 합 $0.04329180 / 최대4 Attempt 계획 $0.17316720. 기존 누적 $0.12810065 포함 $0.30126785.
- 사전 추정은 Provider Usage나 엄밀한 상한이 아니다. 실제 HTTP Attempt 전마다 128K input+해당 cap 미할인 비용을 예약하고 Usage 확인 시 실제 Usage의 미할인 비용으로 정산했다. 알 수 없는 Usage는 예약액을 유지하도록 했다.
- 이번 실제 Usage 기반 추정: 미할인 $0.02092740 / Cache 반영 $0.02027460.
- 최종 누적 보수 추정 $0.14902805 < $0.50. 과거 일부 실험의 Usage/HTTP 기록 한계가 있어 전체 실제 Billing을 증명하는 금액은 아니다.
- 가격은 1M token당 input $0.15 / cached input $0.075 / output $0.60, [공식 gpt-4o-mini 단가](https://developers.openai.com/api/docs/models/gpt-4o-mini). OpenAI 비동기 Batch API 할인은 적용하지 않았다.

## A/B/C/D 반복별 품질

Result=SAFE/FLAGGED, Category=집합 완전 일치, Risk=LOW/MEDIUM/HIGH.
Full tuple은 Result+Category+Risk 전부 일치다. Actionability는 risk != LOW 기준이다.

| 조건 | Run | Result | Category | Risk | Full tuple | Actionability | TP/FP/FN/TN | P/R/F1 | GT HIGH 미달 |
|---|---:|---:|---:|---:|---:|---:|---|---|---:|
| A | 1 | 13/20 | 13/20 | 10/20 | 9/20 | 14/20 | 9/7/0/4 | 0.563/1.000/0.720 | 4 |
| A | 2 | 14/20 | 14/20 | 11/20 | 10/20 | 15/20 | 9/6/0/5 | 0.600/1.000/0.750 | 4 |
| A | 3 | 14/20 | 14/20 | 11/20 | 10/20 | 15/20 | 9/6/0/5 | 0.600/1.000/0.750 | 4 |
| B | 1 | 13/20 | 13/20 | 11/20 | 11/20 | 13/20 | 9/7/0/4 | 0.563/1.000/0.720 | 2 |
| B | 2 | 13/20 | 13/20 | 12/20 | 12/20 | 13/20 | 9/7/0/4 | 0.563/1.000/0.720 | 1 |
| B | 3 | 13/20 | 13/20 | 11/20 | 11/20 | 13/20 | 9/7/0/4 | 0.563/1.000/0.720 | 2 |
| C | 1 | 16/20 | 16/20 | 11/20 | 11/20 | 16/20 | 9/4/0/7 | 0.692/1.000/0.818 | 4 |
| C | 2 | 16/20 | 16/20 | 11/20 | 11/20 | 16/20 | 9/4/0/7 | 0.692/1.000/0.818 | 4 |
| C | 3 | 16/20 | 16/20 | 11/20 | 11/20 | 16/20 | 9/4/0/7 | 0.692/1.000/0.818 | 4 |
| D | 1 | 15/20 | 15/20 | 10/20 | 10/20 | 15/20 | 9/5/0/6 | 0.643/1.000/0.783 | 4 |
| D | 2 | 15/20 | 15/20 | 11/20 | 11/20 | 15/20 | 9/5/0/6 | 0.643/1.000/0.783 | 3 |
| D | 3 | 15/20 | 15/20 | 11/20 | 11/20 | 15/20 | 9/5/0/6 | 0.643/1.000/0.783 | 3 |

### 3회 합산 (조건별 같은20건의 60관측)

| 조건 | Result/Category | Risk | Full tuple | TP/FP/FN/TN | P/R/F1 | HIGH 미달 | 정책 위반 |
|---|---:|---:|---:|---|---|---:|---:|
| A | 41/60 | 32/60 | 29/60 | 27/19/0/14 | 0.587/1.000/0.740 | 12 | 3 |
| B | 39/60 | 34/60 | 34/60 | 27/21/0/12 | 0.563/1.000/0.720 | 5 | 0 |
| C | 48/60 | 33/60 | 33/60 | 27/12/0/21 | 0.692/1.000/0.818 | 12 | 0 |
| D | 45/60 | 32/60 | 32/60 | 27/15/0/18 | 0.643/1.000/0.783 | 10 | 0 |

- FN은 이번 네 조건 모두0이다. 제한된 진단 표본에서 미관측이지 전체 Native/Batch 안전성 증명이 아니다.
- 모든 GT HIGH 미달은 MEDIUM 하향이며 이번에는 HIGH→SAFE가 없었다.
- A의 PROFANITY-13은 FLAGGED/PROFANITY/LOW 3회. 기존 Validator는 허용하지만 Prompt의 LOW 미사용 규칙 위반이다.
- 이 A 오답은 Risk LOW 필드만 GT와 같아 Risk/Actionability 점수를 일부 높일 수 있다. Full tuple과 정책 준수를 별도로 제시하는 이유다.
- B에서는 LOW 위반이 없어졌지만 PROFANITY-13 R2/R3은 FLAGGED/MEDIUM FP로 남았다. 이를 모두 정답 개선으로 계산하지 않는다.

## 세 비교의 새 회귀와 개선

단위는 Case×반복 관측이다. 같은 ID의 세 반복을 서로 다른 고유 Case처럼 세지 않는다.
새 FP/FN은 해당 반복의 왼쪽 조건이 맞혔는데 오른쪽이 틀린 경우다.
새 HIGH 하향은 GT HIGH이고 왼쪽 HIGH였던 결과가 오른쪽에서 낮아진 경우다.

| 비교 | tuple 변경 | 새 FP | 새 FN | 새 HIGH 하향 | Result regression | Category regression | Risk regression | Actionability regression | Full regression | Full 개선 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A→B | 13 | 3 | 0 | 0 | 3 | 3 | 5 | 5 | 3 | 8 |
| C→D | 5 | 3 | 0 | 0 | 3 | 3 | 3 | 3 | 3 | 2 |
| B→D | 22 | 1 | 0 | 8 | 1 | 1 | 12 | 1 | 12 | 10 |

각 축은 같은 변경을 겹쳐 센다. 별도 독립 오류처럼 합산하지 않는다.

### A→B: Native 공통 Prompt 효과

- Result/Category 41→39/60, FP19→21. Full tuple29→34, GT HIGH 미달12→5.
- 신규 FP: PROFANITY-05 R1, SAFE-17 R2/R3. 고유2Case/3관측.
- FP 개선: PROFANITY-13 R1 한 번. R2/R3은 FP를 유지하면서 LOW→MEDIUM으로 바뀌어 정책 위반은 해소했지만 GT 정답은 아니다.
- HIGH 개선: PROFANITY-14 R1/R2/R3, PROFANITY-16 R2/R3, SPAM-08 R1, SPAM-16 R2.
- 새로운 HIGH 하향/FN은 없지만 새 FP가 있어 품질 Gate 통과로 판단하지 않는다.
- SAFE-09/SAFE-10/PI-20 등의 Native 개인정보 오탐은 세 반복 모두 유지됐다.

### C→D: Batch 공통 Prompt 효과

- Result/Category48→45/60, FP12→15. Full tuple33→32, GT HIGH 미달12→10.
- SAFE-09가 S/S/S → PI/M·PI/M·PI/M: 새 FP 고유1Case/3관측.
- PROFANITY-15는 MEDIUM3/3 → MEDIUM/HIGH/HIGH: Risk 개선2관측.
- PROFANITY-14·16의 MEDIUM 하향은 세 번 모두 유지됐다.
- 새 FN/새 HIGH 하향은0이지만 오탐이 늘고 공통 개선이 확보되지 않아 Gate 실패다.

### B→D: 같은 Q1에서 Native와 Batch 차이

- D의 Result/Category45/60는 B39/60보다 높지만 Risk32/60, Full32/60은 B34/60보다 낮다.
- 새 HIGH 하향8관측/고유4Case:
  - PROFANITY-14 / PROFANITY-16: 각각 R1/R2/R3.
  - PROFANITY-15: R1.
  - SPAM-16: R2.
- PROFANITY-13 R1 새 FP1. PROFANITY-01은 B MEDIUM3/3 → D HIGH3/3으로 잘못 상향됐다.
- D에서 PROFANITY-06·PI-20 FP가 세 번씩, PROFANITY-05 FP가 R1에 개선됐다.
- PROFANITY-08은 B MEDIUM3/3 → D HIGH3/3으로 개선됐다.
- 비용이 낮아도 새 HIGH 하향을 총점으로 상쇄해 Production 적용하지 않는다.

## 공통 개선 / 형식별 개선 / 공통 오답

- 같은 Case·반복에서 A→B와 C→D가 **모두 새롭게 정답이 된 공통 개선은0**이다. Full tuple과 Result/Category 기준 모두 같다.
- 같은 Case·반복에서 양쪽이 모두 새로 틀린 공통 회귀도0이다. 이것은 공통 오답이 없다는 뜻이 아니다.
- Native만 개선: PROFANITY-14(3회), PROFANITY-16(2회), SPAM-08(1회), SPAM-16(1회), PROFANITY-13(1회). 주로 Risk 개선이며 괄호는 관측 수다.
- Batch만 개선: PROFANITY-15(2회), Risk만 개선.
- 공통 오답이던 PROFANITY-14/16은 Native에서만 개선되고 Batch에서는 남았다.
- SAFE-10·SPAM-15의 공통 FP는 모든 조건·반복에서 유지됐다. PROFANITY-13도 B R1을 제외하면 FP가 남았다.
- Native용·Batch용 Q1을 동일하게 사용했지만 효과가 달랐다. 요청 형식과 Prompt 효과가 상호작용할 가능성을 보여주나, 특정 추가 문장/Schema/문맥 혼입을 내부 원인으로 확정하지 않는다.

## 전체20건 반복 판정표 / 안정성

S=SAFE/[]/LOW, P=FLAGGED/[PROFANITY], I=FLAGGED/[PERSONAL_INFORMATION], SP=FLAGGED/[SPAM], L/M/H=LOW/MEDIUM/HIGH.
모든 셀은 R1 / R2 / R3 순서. enum 원값은 JSON에 있다.

| Case | GT | A | B | C | D |
|---|---|---|---|---|---|
| SAFE-17 | S | P/M / S / S | P/M / P/M / P/M | P/M / P/M / P/M | P/M / P/M / P/M |
| PI-05 | I/M | I/M / I/M / I/M | I/M / I/M / I/M | I/M / I/M / I/M | I/M / I/M / I/M |
| PROFANITY-14 | P/H | P/M / P/M / P/M | P/H / P/H / P/H | P/M / P/M / P/M | P/M / P/M / P/M |
| SPAM-16 | SP/H | SP/M / SP/M / SP/M | SP/M / SP/H / SP/M | SP/M / SP/M / SP/M | SP/M / SP/M / SP/M |
| SPAM-19 | S | S / S / S | S / S / S | S / S / S | S / S / S |
| SPAM-08 | SP/H | SP/M / SP/H / SP/H | SP/H / SP/H / SP/H | SP/H / SP/H / SP/H | SP/H / SP/H / SP/H |
| SAFE-09 | S | I/M / I/M / I/M | I/M / I/M / I/M | S / S / S | I/M / I/M / I/M |
| PROFANITY-16 | P/H | P/H / P/M / P/M | P/H / P/H / P/H | P/M / P/M / P/M | P/M / P/M / P/M |
| PROFANITY-01 | P/M | P/M / P/M / P/M | P/M / P/M / P/M | P/H / P/H / P/H | P/H / P/H / P/H |
| PROFANITY-06 | S | P/M / P/M / P/M | P/M / P/M / P/M | S / S / S | S / S / S |
| PROFANITY-13 | S | P/L / P/L / P/L | S / P/M / P/M | P/M / P/M / P/M | P/M / P/M / P/M |
| SAFE-01 | S | S / S / S | S / S / S | S / S / S | S / S / S |
| PROFANITY-17 | S | S / S / S | S / S / S | S / S / S | S / S / S |
| SPAM-15 | S | SP/M / SP/M / SP/M | SP/M / SP/M / SP/M | SP/M / SP/M / SP/M | SP/M / SP/M / SP/M |
| PROFANITY-15 | P/H | P/H / P/H / P/H | P/H / P/H / P/H | P/M / P/M / P/M | P/M / P/H / P/H |
| PROFANITY-08 | P/H | P/M / P/M / P/M | P/M / P/M / P/M | P/H / P/H / P/H | P/H / P/H / P/H |
| SPAM-09 | SP/H | SP/H / SP/H / SP/H | SP/H / SP/H / SP/H | SP/H / SP/H / SP/H | SP/H / SP/H / SP/H |
| SAFE-10 | S | I/M / I/M / I/M | I/M / I/M / I/M | I/M / I/M / I/M | I/M / I/M / I/M |
| PROFANITY-05 | S | S / S / S | P/M / S / S | S / S / S | S / S / S |
| PI-20 | S | I/M / I/M / I/M | I/M / I/M / I/M | S / S / S | S / S / S |

| 조건 | full tuple 3회 일치 | 변동 Case |
|---|---:|---|
| A | 17/20 | SAFE-17, SPAM-08, PROFANITY-16 |
| B | 17/20 | SPAM-16, PROFANITY-13, PROFANITY-05 |
| C | 20/20 | 없음 |
| D | 19/20 | PROFANITY-15 |

C는 정답뿐 아니라 오답도 안정적으로 반복했다. B의 HIGH 개선 일부와 D의 PROFANITY-15 Risk 개선에는 반복 변동이 남았다.
3회 일치를 결정성이나 운영 안정성 보장으로 해석하지 않는다.

## 모든 paired tuple 변경

| 비교 | R | Case | GT | 왼쪽 | 오른쪽 |
|---|---:|---|---|---|---|
| A→B | 1 | PROFANITY-14 | P/H | P/M | P/H |
| A→B | 1 | SPAM-08 | SP/H | SP/M | SP/H |
| A→B | 1 | PROFANITY-13 | S | P/L | S |
| A→B | 1 | PROFANITY-05 | S | S | P/M |
| A→B | 2 | SAFE-17 | S | S | P/M |
| A→B | 2 | PROFANITY-14 | P/H | P/M | P/H |
| A→B | 2 | SPAM-16 | SP/H | SP/M | SP/H |
| A→B | 2 | PROFANITY-16 | P/H | P/M | P/H |
| A→B | 2 | PROFANITY-13 | S | P/L | P/M |
| A→B | 3 | SAFE-17 | S | S | P/M |
| A→B | 3 | PROFANITY-14 | P/H | P/M | P/H |
| A→B | 3 | PROFANITY-16 | P/H | P/M | P/H |
| A→B | 3 | PROFANITY-13 | S | P/L | P/M |
| C→D | 1 | SAFE-09 | S | S | I/M |
| C→D | 2 | SAFE-09 | S | S | I/M |
| C→D | 2 | PROFANITY-15 | P/H | P/M | P/H |
| C→D | 3 | SAFE-09 | S | S | I/M |
| C→D | 3 | PROFANITY-15 | P/H | P/M | P/H |
| B→D | 1 | PROFANITY-14 | P/H | P/H | P/M |
| B→D | 1 | PROFANITY-16 | P/H | P/H | P/M |
| B→D | 1 | PROFANITY-01 | P/M | P/M | P/H |
| B→D | 1 | PROFANITY-06 | S | P/M | S |
| B→D | 1 | PROFANITY-13 | S | S | P/M |
| B→D | 1 | PROFANITY-15 | P/H | P/H | P/M |
| B→D | 1 | PROFANITY-08 | P/H | P/M | P/H |
| B→D | 1 | PROFANITY-05 | S | P/M | S |
| B→D | 1 | PI-20 | S | I/M | S |
| B→D | 2 | PROFANITY-14 | P/H | P/H | P/M |
| B→D | 2 | SPAM-16 | SP/H | SP/H | SP/M |
| B→D | 2 | PROFANITY-16 | P/H | P/H | P/M |
| B→D | 2 | PROFANITY-01 | P/M | P/M | P/H |
| B→D | 2 | PROFANITY-06 | S | P/M | S |
| B→D | 2 | PROFANITY-08 | P/H | P/M | P/H |
| B→D | 2 | PI-20 | S | I/M | S |
| B→D | 3 | PROFANITY-14 | P/H | P/H | P/M |
| B→D | 3 | PROFANITY-16 | P/H | P/H | P/M |
| B→D | 3 | PROFANITY-01 | P/M | P/M | P/H |
| B→D | 3 | PROFANITY-06 | S | P/M | S |
| B→D | 3 | PROFANITY-08 | P/H | P/M | P/H |
| B→D | 3 | PI-20 | S | I/M | S |

## Token / 비용 / Latency

Batch Usage는 요청별 한 번만 더한다. Case별 사용량으로 복제하거나 임의 배분하지 않는다.

| 조건 | 논리/HTTP | Input | Output | Cached Input | Total | 미할인 USD | Cache 반영 USD | avg/p95/p99 ms | 조건별 처리 합 ms |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|
| A | 60/60 | 49317 | 1054 | 0 | 50371 | $0.00802995 | $0.00802995 | 970.6/1430.3/2792.5 | 58277.4 |
| B | 60/60 | 55197 | 1064 | 0 | 56261 | $0.00891795 | $0.00891795 | 852.0/1154.8/1446.5 | 51144.9 |
| C | 6/6 | 6879 | 1515 | 4096 | 8394 | $0.00194085 | $0.00163365 | 2985.2/3591.1/3591.1 | 17923.2 |
| D | 6/6 | 7467 | 1531 | 4608 | 8998 | $0.00203865 | $0.00169305 | 2792.0/3020.0/3020.0 | 16759.1 |

- 합계 Input118860 / Output5164 / Cached8704 / Total124024. Cached는 Input의 부분집합이다.
- Q1의 실제 Provider input은 Native와 Batch 모두 요청당98token 증가했다. 로컬 tokenizer의 System 차이와 같지만 Usage에서 System/Schema 각 부분을 별도 청구 항목으로 분리 확인한 것은 아니다.
- A input/call821.95 → B919.95, C1146.5 → D1244.5.
- Q1 자체는 Token 절감 후보가 아니다. B보다 D가 적은 비용으로 실행됐지만 품질 회귀가 있어 채택 근거로 삼지 않는다.
- logical latency는 요청부터 ChatResponse까지이며 p95/p99는 nearest-rank다. Batch 조건은 n=6이라 p95/p99가 최대값이다.
- 조건별 시간 합은 교차 실행 중 해당 요청 시간 합이다. 사용자 대기·maxWait·Kafka·DB를 포함한 E2E가 아니다.
- Cache/시점/backend/표본 수 차이가 있어 Q1이 지연을 개선했다는 인과 주장은 하지 않는다.
- 요청별 Usage/latency/HTTP/시각은 raw JSON calls/httpAttempts에 전부 있다.

## 실행 / 무과금 회귀 / 보존

실제 실행한 Gradle 명령 (아래 유료 두 명령만 안전한 Key 자식 환경 사용):

```sh
env -u OPENAI_API_KEY ./gradlew :test --tests com.bobfull.chat.infrastructure.ai.Issue55CommonPromptEvaluationTest -PshowTestOutput --console=plain
./gradlew --no-daemon :test --tests 'com.bobfull.chat.infrastructure.ai.Issue55CommonPromptOpenAiTest.자식프로세스_Key_존재만_확인하고_Provider는_호출하지_않는다' -PshowTestOutput --console=plain
./gradlew --no-daemon :test --tests 'com.bobfull.chat.infrastructure.ai.Issue55CommonPromptOpenAiTest.진단20건_ABCD를_3회씩_132호출만_비교한다' -PshowTestOutput --console=plain
env -u OPENAI_API_KEY ./gradlew :test --tests com.bobfull.chat.infrastructure.ai.Issue55CommonPromptEvaluationTest --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluationTest --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparisonTest --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchF0Test --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatComparisonTest --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchSizeComparisonTest --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosisTest --tests com.bobfull.chat.infrastructure.ai.Issue55MicroBatchChecklistTest --tests com.bobfull.chat.infrastructure.ai.ModerationHeldoutDatasetTest --console=plain
env -u OPENAI_API_KEY ./gradlew build -x test --console=plain
```

- 신규 Fake13 PASS: 네 문장 원문 보존/132 schedule/동일 wire/역순 ID/Native·Batch Parse·Validation·잘림/ID 누락·중복·미요청/Usage 중복 방지.
- Key 존재만 확인1 PASS, Provider0회.
- 실제 비교 실행1 PASS, 132logical/132HTTP. 품질 Gate는 별도로 실패.
- 최종 무과금 관련 회귀107 tests / skipped0 / failures0 / errors0 PASS.
- root + lambda build -x test PASS. 전체 test suite와 다른 유료 실험은 재실행하지 않았다.
- 독립 Python 재계산으로 Result/Category/Risk/Full/FP/FN/Usage/ledger를 교차 확인했다.
- raw build JSON과 Evidence JSON의 값 동일, 실행 소스 해시 동일, 44셀 반복 wire 동일, 조건별 매 Run 고유20ID 일대일 확인.
- 기존 tracked/staged diff 없음. 착수 시 기존1106파일의 SHA-256이 모두 동일하며 수정·삭제·기존 Evidence 덮어쓰기 없음.
- 신규 테스트 Java3개와 별도 Evidence3개만 추가했다. git diff --check 및 신규6개 untracked 파일의 git diff --no-index --check 검사 PASS. credential 형식 literal/인증 header 미포함 검사 PASS.
- 독립 Python으로 paired 새 FP/FN/HIGH 하향·Full tuple 개선/회귀를 다시 계산했고, 양쪽 공통 신규 개선/회귀0도 일치했다.
- Commit/Push/PR/Issue 변경 없음. 신규40건 파일도 수정하지 않았다.

## 최종 판정 / 다음 단계

| 질문 | 판정 |
|---|---|
| Q1을 현 상태로 채택할 수 있는가 | 아니오. Native/Batch 모두 새 FP가 있고 공통 개선이 확보되지 않았다. |
| Q1을 계속 검증할 가치가 있는가 | Native의 위협 Risk 개선 신호는 있으나 전체 Q1을 그대로 80/40건으로 확대할 근거는 부족하다. 다음 실험 여부는 Human 판단 사항이다. |
| Native/Batch에 공통 적용 가능한가 | 현 실험으로 확인하지 못했다. Native 개선이 Batch에 이전되지 않았고 B 대비 D의 새 HIGH 하향도 남았다. |
| 이번 진단에서 FN이 발생했는가 | 네 조건 모두0. 작은 사후 선정 진단 표본의 결과로 한정한다. |
| 비용 절감만으로 Batch를 적용할 수 있는가 | 아니오. 새 HIGH 하향/FP와 Kafka·대기·부분 실패·멱등성·개인정보 분리 등 미검증 운영 경계가 남는다. |
| 다음 자동 실행 | 없음. Q1 고정 상태로 결과를 보고하고 중단한다. 80건/신규40건 평가와 Q1 수정은 별도 승인 전 하지 않는다. |

## 관련 자료

- [원본 호출 기록](common-prompt-q1-diagnostic.json)
- [산출 지표·paired 변화·전체 반복 판정](common-prompt-q1-summary.json)
- [기존 전체 비교](microbatch-comparison.md)
- [기존 F0](microbatch-f0.md)
- [기존 Native/Batch1 형식 대조](microbatch-format-a.md)
- [기존 Checklist](microbatch-b10-checklist-diagnostic.md)
