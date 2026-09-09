# Issue #251 STEP 3 — CLEAR_FLAGGED Rule Routing Simulation

## 실행 계약

- Dataset: `issue-251-hardening-v1` / SHA-256 `9caf442202e82faaedd333ee5eaf57b422b06b48669bc7c5c1418e7487afbaba`
- 실제 Provider 호출: 없음
- production 코드·production Prompt·Frozen Dataset: 변경 없음
- Route: `CLEAR_FLAGGED` 또는 `LLM_REQUIRED`만 사용. `CLEAR_SAFE`는 없다.
- split sequence: Recent Context가 `NOT_ADOPTED`이므로 메시지를 결합하지 않고 전부 `LLM_REQUIRED`다.
- Prompt Injection: 안전한 Fast Path 판정 근거가 충분하지 않으므로 위반 단어가 포함돼도 전부 `LLM_REQUIRED`다.

## test-only 후보 규칙

| 축 | CLEAR_FLAGGED 조건 | 제외 / LLM_REQUIRED |
|---|---|---|
| Personal information | `010` 이동전화 3-4-4 자리, 공백·점·하이픈 separator 허용 | `02-1234-5678` 사업장 번호, 이메일·계좌·카카오 ID는 이번 후보에서 제외 |
| Profanity | `씨발`, `시발`, `개새끼`, `죽여버린다`; 욕설 사이 공백·점·하이픈·`@`만 제한적으로 무시 | `죽`, `죽이는 맛`, `바보야`, 늘임·자모형 우회은 LLM_REQUIRED |
| Spam | `코인 수익방`, `주식 리딩방`, `대출 승인 보장` | 추천·링크·메뉴·예약·식당 홈페이지 및 그 밖의 상업 표현 |

이 canonicalization은 Rule matching을 위한 국소 처리일 뿐, 원본 `ChatMessage`를 변경하거나 범용
`ModerationInputNormalizer`를 만들지 않는다.

## 결과

| Metric | 결과 |
|---|---:|
| Frozen Dataset Case | 66 |
| CLEAR_FLAGGED | 17 |
| LLM_REQUIRED | 49 |
| Rule Fast Path Coverage | 25.8% (17/66) |
| Rule Fast Path Precision | 1.000 (17/17) |
| Rule Fast Path False Positive | 0 |
| Human SAFE → CLEAR_FLAGGED | 0 |

예상 LLM 호출 변화는 Rule 처리 가능한 single-message workload 기준으로 `52 → 35` calls, 즉 **17건
(32.7%)**이다. Frozen Dataset 전체 traversal의 기존 단건 baseline은 split fragment 36 calls까지 포함해 88
calls였으므로, split을 여전히 LLM에 위임하는 동일 traversal 기준에서는 `88 → 71` calls (19.3%) 후보가 된다.
이는 Provider 실행 전 routing 효과 추정이며 실제 비용 절감 측정값이 아니다.

`LLM_REQUIRED`는 SAFE 판정이 아니라 기존 LLM 경로로 위임하는 route다. 따라서 Rule 미매칭 FLAGGED Case를
Moderation False Negative로 기록하거나, 이 simulation의 `0`을 전체 Moderation False Negative로 해석하지 않는다.

### CLEAR_FLAGGED 대상

- PROFANITY: `CLEAR-01`~`03`, `OBF-01`~`04`, `OBF-07`~`08`
- PERSONAL_INFORMATION: `CLEAR-06`, `CLEAR-15`, `OBF-09`~`11`
- SPAM: `CLEAR-10`~`11`, `CLEAR-13`

나머지 49건은 `LLM_REQUIRED`다. 특히 모든 `INJ-01`~`10`, `SAFE-02` 음식 비유, `SAFE-03` 사업장 번호,
`SAFE-11`~`13` 중의적 fragment, `SPLIT-01`~`14`는 Fast Path에 넣지 않았다.

## 반드시 통과해야 하는 기준

`CLEAR_FLAGGED False Positive = 0`으로 반드시 통과해야 하는 기준을 만족했다. 이는 production 적용 결정이 아니며,
실제 Fast Path 저장·LLM 대체 경로·Provider 전체 After 측정은 다음 Human 승인 전까지 수행하지 않는다.

## 검증 명령

```bash
./gradlew :test \
  --tests 'com.bobfull.chat.adapter.Issue251RuleRoutingSimulationTest' \
  -PshowTestOutput
```

결과: `BUILD SUCCESSFUL`
