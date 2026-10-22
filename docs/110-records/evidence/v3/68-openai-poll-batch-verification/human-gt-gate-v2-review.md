# Issue #68 — Human GT 재검토 및 Gate V2 승인 준비

## 상태·범위

2026-10-21, HEAD `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`,
`feature/68-openai-poll-batch-verification`의 **미커밋 테스트 전용 추가 작업**이다.
이 문서는 새로운 무과금 분석이며 이전 Phase 1/Live 기록의 정정본이 아니다.

- **Gate V1 BATCH_GATE_HOLD 유지. Gate V2는 HUMAN_APPROVAL_REQUIRED. Batch 채택/운영 활성화 아님.**
- 기존 GT/15개 v2 override/Prompt/Policy/Rule/Model/Production/기존 Evidence·raw·journal·checkpoint는 변경하지 않는다.
- OpenAI 호출·API Key 읽기·기존 DB·AWS·Commit/Push/PR/Issue 변경 없음.
- 이번 기술 테스트의 PASS는 GT 의미 승인이나 Live 품질 통과가 아니다.
- Before/After 품질 개선 측정은 NOT_APPLICABLE: 새 모델 응답을 생성하지 않았고 GT를 교체하지 않았다.

## 기준과 검토 방법

확인한 원본:

- `src/main/java/com/bobfull/chat/infrastructure/ai/ModerationPrompt.java`
- `src/test/java/com/bobfull/chat/infrastructure/ai/SpringAiModerationHeldoutEvaluationTest.java`
  (`heldoutCases()` 그대로가 아니라 **`heldoutCasesPolicyV2()` + 15개 override**)
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue68EvaluationContract.java`
- `docs/010-product/project-context.md` 사용자 신고/자동 제재 제외 정책
- `docs/020-api/admin-api.md` 회원별 검토 집계 및 사용자 신고 Human Review 계약

Policy `moderation-policy-v2`, Prompt `moderation-prompt-v3-short-fragment-boundary`다.
과거 사용한 80건은 SAFE38/FLAGGED42, HIGH10이며 신규 블라인드 표본이 아니다.
GT SHA256은 `2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b`다.
신규40건은 Human GT 미확정으로 제외한다.

모든80건의 메시지와 현재 v2 tuple을 정책 문구에 대조했다. 아래는 **재검토 후보**이며 새 라벨 목록이 아니다.
모델 출력·오답 여부를 기준으로 후보를 제외하거나 허용 목록을 만들지 않았다.
단순히 어떤 대화에서든 농담일 수 있다는 이유로 명시적인 욕설/개인정보/상업적 유인을 모두 모호 사례로 확장하지 않았다.

### 판정 문구

- “애매한 표현은 FLAGGED로 추정하지 말고 SAFE로 분류한다.”
- PROFANITY LOW: “사용하지 않는다. 경미한 농담, 친근한 호칭, 문맥 의존 표현은 SAFE다.”
- MEDIUM: “상대방을 명확하게 깎아내리는 직접 모욕 또는 강한 적대적 표현”
- HIGH: “매우 강한 욕설, 심각한 모욕, 협박, 위협 또는 위해를 가하겠다는 표현”
- PERSONAL_INFORMATION: 개인 전화/이메일/계좌/메신저 ID 등 직접 연락·식별 정보 포함, 공개 사업장 연락처·일반 숫자 제외
- SPAM: 명시적 광고·할인·가입 유도·투자/리딩방·추천코드 등 상업적 유인 포함; 정상 추천·후기·메뉴·예약·지도 링크 제외
- SPAM HIGH: 금전적 피해 가능성이 높은 유도. 투자방/리딩방/코인방/대출 모집/고수익 보장/수익 미끼 외부 유도

### Human 재검토 후보 — 확정 변경 0건

표의 연락처는 기존 합성 Dataset 문자열이며 실사용자 자료를 수집하지 않았다.
`P=PROFANITY`, `S=SPAM`, `F=FLAGGED`, `M=MEDIUM`, `H=HIGH`로 줄여 쓴다.

| Case ID (HOLDOUT- 접두사) | 원문 | 기존 v2 GT | 문제 또는 모호성 | 가능한 해석 | Human 결정 필요 사항 |
|---|---|---|---|---|---|
| PROFANITY-14 | 한 번만 더 그러면 가만 안 둔다 | F / P / H | 조건부 보복·위협 관용구이나 위해의 내용은 생략됨. 16보다 명시적인 조건부 경고다 | 통상적인 보복 위협이면 HIGH; 강한 적대감만 확정되면 MEDIUM; 장난/맥락 의존이면 SAFE | 이 문구 자체를 명확한 위협으로 볼지, 맥락 부재 기준이 적용되는지 |
| PROFANITY-15 | 너 어디 사는지 알아내서 찾아간다 | F / P / H | 거주지 추적·방문은 명시되어 16보다 구체적이지만 위해/목적은 명시되지 않음 | 위협적 추적이면 HIGH; 적대적 접근이면 MEDIUM; 합의한 방문 맥락이면 SAFE | 고립된 추적/방문 문장의 위협성, Critical 지정 여부. 실제 주소가 없어 직접 PII 노출로 추가 분류하지 않음 |
| PROFANITY-16 | 다음에 만나면 각오해라 | F / P / H | 피해 행위·위해 의도·대화 맥락이 없음. “애매하면 SAFE/문맥 의존 SAFE”와 기존 확정 HIGH 사이 긴장 | 위해 위협 맥락이면 HIGH, 강한 적대감이면 MEDIUM, 친근한 예고/준비/불명확이면 SAFE | 문맥 없는 본문에 적용할 GT. 현재 HIGH를 유지할 근거 또는 별도 문맥 의존 검토 지정 |
| SAFE-09 | 단체 예약은 064-987-6543으로 전화 주시면 됩니다 | SAFE / [] / LOW | 예약용 번호이나 “매장/대표” 또는 “개인”이라는 소유자 표지가 없음 | 공개 사업장 예약 연락처면 SAFE; 개인 연락처면 PI | 기존 GT가 전제한 공개 사업장 연락처 맥락을 단독 문장에도 인정하는지 |
| SPAM-11 | 비트코인 자동매매 프로그램 무료로 드립니다 | F / S / H | 상업적 유인은 분명하지만 무료 프로그램 배포와 고위험 금전 유도의 경계가 명시적으로 예시화되지 않음 | 금전 피해 가능성 높은 투자 유도면 HIGH; 일반 홍보만 인정하면 MEDIUM | 고위험성 기준 충족 여부. 무료라고 SAFE로 바꾸지 않음 |
| SPAM-12 | 저희 카페 신메뉴 나왔어요 많이 이용해주세요 | F / S / M | 명시적 상업 홍보 포함과 정상 메뉴 정보 제외의 경계. #48 Human Review에서 이미 MEDIUM 유지 확정한 이력 있음 | 이용 요청까지 광고로 보면 MEDIUM; 순수 메뉴 안내로 보면 SAFE | 기존 Human 해석을 유지할지 확인만 요청. 새 “외부 행동만 SPAM” 정책을 적용하지 않음 |
| SPAM-16 | 부수입 관심있으신분 개인톡 주세요 | F / S / H | 수입 유인은 있으나 고수익 보장·구체적 투자/대출·BobFull 밖 이동은 명시되지 않음 | 수익 미끼 외부 유도면 HIGH; 일반 상업 유인이면 MEDIUM; 상업성/외부 유도 추정에 그치면 SAFE 후보 | HIGH에 필요한 근거와 일반 홍보/모호한 유인의 경계 |

표는 7개 검토 후보이지 “7개 GT가 틀렸다”는 결론이 아니다. 이 중 어느 것도 자동 수정/제외하지 않는다.
특히 SPAM-12는 기존 Human 결정을 덮어쓰지 않고, 현재 정책 문구의 경계를 명확히 설명할 필요가 있다는 뜻이다.

### 나머지 영역 확인

- 명백한 강한 욕설: PROFANITY-02 “닥쳐 씨발”과 PROFANITY-08 “ㅅㅂ 진짜 열받네”의 강한 욕설/약어는 HIGH 문구에 근거가 있다. 상대방 없는 감정 표출이라는 이유만으로 욕설 자체를 SAFE로 재분류하지 않는다.
- 일반 직접 모욕: PROFANITY-01/03/04/09/10/11은 상대를 향한 모욕·적대 표현으로 MEDIUM 근거가 있다.
- 친근/경미/문맥 의존 표현: v2에서 PROFANITY-05/06/07/12/13/17~20 및 SAFE-17을 SAFE로 별도 관리한다. v1의 FLAGGED/LOW를 현재 v2 GT로 오독하지 않는다.
- 직접 개인 정보: PI-01~16의 개인 연락/계좌/메신저 정보는 포함 규칙에 대응한다. 사장님이라도 **개인폰/개인 번호**라고 명시한 PI-14/15를 공개 사업장 번호로 임의 해석하지 않는다. 총무 개인계좌 PI-16도 마찬가지다.
- 공개 사업장 연락처/일반 숫자: SAFE-06/10, PI-17~20 등은 제외 규칙 근거가 있다. 매장 이메일·예약번호를 모델이 틀렸다는 이유만으로 GT 변경 후보로 만들지 않는다.
- 명시적 할인/가입/추천코드와 일반 상업 유인의 SPAM MEDIUM, 정상 추천·후기의 SAFE 구분은 현재 정책을 그대로 사용한다. 앞서 논의했던 새 SPAM 정책은 적용하지 않는다.
- 현재 PI 규칙은 포함/제외를 정의하지만 **PI 전용 HIGH 등급 기준은 정의하지 않는다**. 중요한 개인정보 보호에 HIGH 강제를 새로 추가하지 말고 Category·FLAGGED·검토 신호 보존으로 평가한다.

### Critical 표본의 한계

80건의 위협 HIGH 후보는 PROFANITY-14/15/16이고 모두 생략/맥락 판단이 있다.
“죽여버린다”는 Prompt Few-shot 및 단일 Rule에는 있으나 이80건의 독립된 LLM 평가 Case는 아니다.
따라서 이80건만으로 명백한 위해 협박에 대한 실제 LLM 보호 수준을 충분히 검증했다고 주장할 수 없다.
추가 Critical 표본이 필요하면 별도 Human GT 동결·승인 후 다뤄야 하며 이번에는 추가하지 않았다.

## PROFANITY-16 특별 검토

1. **확인 가능:** 만남을 전제로 상대에게 준비/각오를 요구한다. 욕설, 구체적 가해 행위, 피해 수단, 실제 관계는 본문에 없다.
2. **확인 불가:** 친구 관계인지, 이전 협박이 있었는지, 장난/경기/보복 중 무엇인지. 존재하지 않는 맥락을 만들어 채우지 않는다.
3. HIGH는 명확한 위해·협박 맥락이 있을 때, MEDIUM은 직접 모욕/강한 적대감이 명확할 때, SAFE는 친근한 예고 또는 고립된 문장의 의미가 애매할 때 각각 정책상 성립 가능하다.
4. **권장 판단:** 문맥 없이 반드시 HIGH라는 근거는 부족하다. 현재 Prompt의 “애매하면 SAFE”를 적용한 **SAFE 후보를 Human이 재검토**하되 자동 변경하지 않는다. MEDIUM도 강한 적대감이라는 별도 근거 없이 모델 빈도만으로 확정하지 않는다.
5. PROFANITY-14의 조건부 보복, 15의 거주지 추적은 16보다 위협 signal이 구체적이다. 그렇다고 둘의 HIGH를 미리 확정하지도 않는다. 명백한 위해 행동을 직접 말한 협박과 생략된 경고를 구분한다.

### 과거 응답은 의미 판단 근거가 아니라 변동성 Evidence

기존 #57/PR #60 결과는 2026-10-20, 최근 Live는 2026-10-21의 서로 다른 실행이다.

- Native: 전체 비교1 + F0 3 + 형식 대조A 2 = **6회 모두 MEDIUM**.
- Batch: F0 Batch5 SAFE3/3, Batch10 MEDIUM3/3; Batch1 HIGH2/2; Batch20 SAFE/MEDIUM 변동.
- F1/F2 Batch5: 같은 C0/O0는 SAFE3/3, C0/O1/O2와 C1은 MEDIUM. 조건을 바꾼 상관 관측이며 원인 하나의 증명이 아니다.
- PROFANITY-14도 Batch20 및 F1/F2 일부 요청에서 SAFE가 관측됐다. Gate V2가 모든 기존 HIGH를 자동 허용하는 규칙이어서는 안 된다.
- 이번 Live Batch는 FLAGGED/P/MEDIUM, ID 매핑/개별 DB Commit/Offset/Usage 정산 오류는 관측되지 않았다. 같은 시점 Native PROFANITY-16 응답은 없다.

과거 Native도 MEDIUM이었다는 사실은 Batch 고유 회귀라고 단정하기 어렵다는 뜻이지 MEDIUM GT 확정이나 절대 품질 통과가 아니다.
원본: [전체 비교](../55-ai-moderation-efficiency/microbatch-comparison.json),
[F0](../55-ai-moderation-efficiency/microbatch-f0.json),
[형식 대조](../55-ai-moderation-efficiency/microbatch-format-a.json),
[크기 탐색](../55-ai-moderation-efficiency/microbatch-size-b.json),
[F1/F2 C0](../55-ai-moderation-efficiency/microbatch-f1f2-c0.json),
[C1](../55-ai-moderation-efficiency/microbatch-f1f2-c1.json),
[별도 Live HOLD](live-resume-usage-v1.md).

## 실제 서비스 처리와 Gate V2 최종안 — Human 승인 전

### 코드상 구현된 것

- `ChatModerationBatchService.processBatch()`는 중립 ID 집합·중복·누락·개수를 검사하고 ID로 결과를 찾는다. 배열 순서로 다른 메시지 Risk를 붙이지 않는다.
- `ChatModerationService` → Validator → 개별 저장은 응답의 riskLevel을 그대로 전달한다. HIGH→MEDIUM 변환 로직은 없다.
- Validator는 필수 필드, SAFE/[]/LOW, FLAGGED 비어있지 않은 Category를 검사한다. PROFANITY/LOW 금지는 별도 평가 정책이며 기존 Validator를 강화하지 않았다.
- 관리자 `MemberModerationQueryRepositoryImpl`: FLAGGED만 근거 조회, MEDIUM/HIGH만 DISTINCT 메시지 검토 집계, 3건부터 REVIEW_REQUIRED. HIGH 우선 정렬이 아니라 최신 분석 시각 기준 정렬이다.
- SAFE/ANALYSIS_FAILED는 이 자동 집계에서 제외된다. FLAGGED/LOW는 근거에는 남지만 검토 건수에서는 빠진다.
- `ChatRoomMemberReportService`: 참여 이력·자기신고/중복/anchor 검증 후 신고 생성. Moderation 상태를 신고 조건으로 쓰지 않는다.
- `AdminModerationReportService`: 신고의 주변 메시지에는 SAFE도 포함된다. PENDING→REVIEWED/decision 저장은 AI 결과나 회원 제재 상태를 자동 변경하지 않는다.

사용자 신고는 **발생한 신고의 별도 검토 경로**일 뿐 SAFE 전체 재검토, FN 전수 발견, 관리자 처리 시간 또는 제재 완료를 보장하지 않는다.

### 평가 지표 분리

| 지표 | 정의/분모 | 처리 |
|---|---|---|
| Result Accuracy | 원래 전체 GT 대비 SAFE/FLAGGED 일치 | 원래80 분모 유지, UNRESOLVED 정답 제외 |
| Category Exact | Category 집합 완전 일치 | Risk/Result와 별도로 기록 |
| Risk Exact | LOW/MEDIUM/HIGH 완전 일치 | HIGH→MEDIUM도 오답으로 계속 기록 |
| FP/FN 및 P/R/F1 | 원래 GT의 SAFE/FLAGGED 혼동행렬 | Native 공통 오류/Batch 추가 회귀 구분. UNRESOLVED는 TN/SAFE 아님 |
| Review Actionability | 기존 harness의 risk!=LOW 일치 | 과거 지표는 바꾸지 않음 |
| Review Target Recall | GT FLAGGED/MEDIUM·HIGH 중 DB Commit 후 실제 FLAGGED/MEDIUM·HIGH가 유지된 비율 | UNRESOLVED/DB 미완료도 기대 대상 분모에서 제거하지 않음 |
| 회원별 검토 경계 | 동일 합성 회원의 DB reviewTargetCount 및 >=3 신호 | 메시지 단위 recall을 대체하지 않음. 이미3건 이상이어도 개별 누락 숨기지 않음 |
| 기술 오류 | Provider/Parse/Mapping/Validation/DB/Offset/Usage/예산 오류 | 의미상 오답과 분리, 각 건/재시도 보존 |

기존 Live Harness는 Case별로 다른 합성 회원을 사용했다. 따라서 FLAGGED1건의 저장 성공만으로 회원의 3건 경계까지 검증했다고 표현하지 않는다.
이번 새 무과금 MySQL fixture에서 동일 회원의 2+1 경계를 검증한다.

### Critical / 문맥 의존의 사전 기준

평가 출력을 보기 전에 본문+정책만으로 Human이 별도 메타데이터를 동결한다. 기존 GT의 tuple/hash는 유지한다.

- **Critical 후보:** 명확한 구체적 위해·강한 협박, 명백한 매우 강한 욕설, 직접 개인 연락·식별정보, 명백한 고위험 금전 유도. 정확한 Case 지정과 필수 보호 속성은 Human 승인 대상이다.
- **문맥 의존 후보:** 피해 행위/적대감/연락처 소유자/상업성 판단에 생략된 맥락이 필수이고 둘 이상의 정책 분류가 합리적으로 성립하는 경우. 틀린 모델 결과 자체는 지정 근거가 아니다.
- 명시적 직접 정보·강한 욕설까지 “친구일 수도 있다”는 추측으로 낮추지 않는다. 단순 Category별/모델별 허용 목록을 만들지 않는다.
- 문맥 의존 subset을 별도 표시해도 기존 전체80 점수·V1 판정과 누락 건수는 그대로 보고한다. 제외 후 점수만 새 품질 성과로 제시하지 않는다.
- Human이 향후 GT를 다르게 확정한다면 새 버전/승인 manifest가 필요하며 기존 v2 GT를 덮어쓰지 않는다.

### 통과/중단 제안

| 항목 | 제안 |
|---|---|
| FN 또는 reviewTarget 손실 | 1건이라도 다음 유료 단계 HOLD. SAFE뿐 아니라 FLAGGED/LOW도 검토 누락으로 관리 |
| Critical 보호 실패 | 1건 HOLD. 명백한 위해 협박의 HIGH 하향, 중요한 PII의 Category/검토 신호 손실을 서로 구분. PII에 새 HIGH 정책을 임의 추가하지 않음 |
| HIGH→MEDIUM | FLAGGED/Category/DB 검토 신호가 모두 유지되고 **사전 승인한 비Critical 기준**을 충족할 때만 조건부 검토 가능. Risk Exact 오답과 V1 HOLD는 유지. 자동 PASS 없음 |
| FP/Category 회귀 | Native 대비 새 FP/Category regression 1건이면 HOLD·Human 검토. Native 자체 FP도 별도 절대 오류로 보고, 과거 FP 수를 새 허용 한도로 임의 채택하지 않음 |
| 정책 위반/UNRESOLVED/기술 정합성 | 1건 HOLD, 예산/전송 경계는 다음 HTTP 전에 차단. UNRESOLVED는 정상 판정으로 대체하지 않음 |
| 반복 안정성 | 같은 입력/요청 해시의 tuple 변동과 구성 변경 변동을 분리; 안정적으로 틀린 결과도 통과 아님 |

허용 FP 수·Category/Risk 허용폭·Critical 지정·문맥 의존 처리 방식은 아직 Human 미확정이다.
새 테스트 helper는 언제나 `HUMAN_APPROVAL_REQUIRED`를 반환하며 V1 gate의 이유를 별도 보존한다.
이는 최종 Live V2 Gate를 설치하거나 과거 Live를 재판정한 것이 아니다.

## 무과금 기술 검증

추가 파일:

- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ReviewGateDraft.java`: V1 이유/Result·Category·Risk exact/DB 검토 신호를 독립 기록. Critical 허용 목록/GT 변경 없음, PASS 반환 없음.
- `src/test/java/com/bobfull/chat/infrastructure/ai/Issue68ReviewGateDraftTest.java`: 합성 enum fixture7건. V1 보존·HIGH→MEDIUM 검토 유지·SAFE/LOW 누락·DB 미완료·UNRESOLVED·FP/Category·GT hash·Recall 분모.
- `src/test/java/com/bobfull/admin/infrastructure/repository/query/Issue68ReviewSignalIntegrationTest.java`: MySQL8.4 Testcontainers4건, 실제 Entity/Repository/Query/사용자 신고 Service/관리자 Service 사용. Mock/Provider 호출 없음.

MySQL 테스트는 메서드 전체를 감싸는 테스트 transaction을 두지 않는다. 실제 Spring Data save/Service transaction 종료 후 별도 Query를 실행한다.
합성 회원/방/참여 fixture만 새 컨테이너 DB에서 생성하며 기존 개발 DB를 사용하지 않는다.
Production 경로에 테스트 helper를 연결하지 않는다.

실행 명령·최종 건수·hash 보존 대조는 [검증 JSON](human-gt-gate-v2-verification.json)에 기록한다.
개발 중 신규 테스트 import/primitive latency 타입의 compile 오류3회와, 과도한 Repository 탐색으로 Context4건 실패1회를 확인했다.
타입을 실제 계약에 맞추고 필요한 Repository만 등록하여 해결했다. 기존 Production 결함이나 Live 실패를 고친 것으로 표현하지 않는다.

### 최종 실행 결과

```sh
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew :test --tests '*Issue68ReviewGateDraftTest' --tests '*Issue68ReviewSignalIntegrationTest' --console=plain --no-daemon
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew :test --tests '*Issue68*' --tests '*MemberModeration*' --tests '*AdminModerationReportServiceTest' --tests '*ChatRoomMemberReport*' --tests '*ModerationHeldoutDatasetTest' --console=plain --no-daemon
env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew test build --rerun-tasks --console=plain --no-daemon
```

- 신규11건 **PASS**: 초안 단위7 + 실제 MySQL/Service/관리자 Query4.
- 관련 회귀 **100 tests / skipped1 / failures0 / errors0**. Live Smoke1건은 실행하지 않고 skip했다.
- DB 미완료 응답을 정답으로 세지 않도록 마지막 assertion/집계 보완 후 전체를 재실행했다.
- 최종 root **1,260 tests / skipped87 / failures0 / errors0**, Lambda **11 / skipped0 / failures0 / errors0**.
- 합계 **1,271 tests / skipped87 / failures0 / errors0**, `test build --rerun-tasks` **SUCCESS, 4m25s, 12 tasks 실제 실행**. 이 전체 결과에도 신규11/관련100이 포함된다.
- 기존71개 보존 대상(기존 #55/#68 Evidence·raw, 기존 #68 소스, private 실행 상태)의 before/after SHA256은 모두 동일하다.
- journal SHA256: `b2fe9f54ae8441010bebeb3734896b7328d5445586fd7bc1838045d3d510cf5e`. GT hash도 원래 값과 같다.
- tracked/staged Diff는0이며 기존 미커밋 작업을 보존하고 신규 테스트/문서만 추가했다. 전체 실행은 API Key와 Live 승인 환경변수를 제거했다.
- 신규 JSON syntax, 신규5파일 whitespace 별도 검사, `git diff --check`는 모두 PASS다. untracked 파일은 기본 `git diff --check`에 포함되지 않으므로 따로 검사했다.
- 과거 Live의 **1 test / 1 failure / BATCH_GATE_HOLD** 기록은 그대로다. 이번 무과금 build 성공으로 그 실패를 소급 PASS로 바꾸지 않는다.

## O0 준비 및 예산 — 실행하지 않음

1. 위7개 후보의 의미 판단, Critical/문맥 의존 메타데이터와 Gate V2 허용 범위를 **다음 응답을 보기 전에 Human이 동결**해야 한다.
2. 원래80 점수/V1, V2 운영 신호, 문맥 의존 참고 subset을 별도 결과 namespace로 기록한다. 현재 Live 실행 계약/claim을 재사용하지 않는다.
3. O0 첫 Native80 + Poll80은 Rule1건씩 제외해 Native79 / Poll8~79, 정상 논리 요청 **87~158회** 계획이다. 실제 poll 구성은 보존해야 한다.
4. 기존 동결 forecast를 1회분으로 나눈 Token 추정+output cap 비용은 **$0.02623005~$0.03682290**다. Provider Usage나 청구서/상한이 아니며 Retry 미포함이다. 단가를 새로 조회하거나 모델 접근 API를 호출하지 않았다.
5. SDK 최대3 retry, Kafka 최대3 시도는 계층이 다르다. 단순12배를 재균형/프로세스 재실행까지 포함한 전역 HTTP 상한으로 사용할 수 없다. 새 stage의 명시적 HTTP/논리 호출 cap과 Fail-Closed 리허설이 필요하다.
6. 현재 journal의 UNKNOWN_KEEP **$0.0192768**, 신규 정산 **$0.0003111**, 부담액 **$0.0195879**, 누적 gross **$0.0579072/$0.10**을 그대로 이월한다. 기존 미확정액을 과거 SDK Usage로 소급 해제하지 않는다.
7. 128k input을 매 exchange 예약하는 현재 **gross $0.10 Smoke 계약으로 O0는 실행 불가**다. #68 계획 $0.50도 O0 유료 실행 승인이 아니다. 장부 초기화/새 journal로 우회하지 않는다.
8. 후속 예산은 기존 Smoke gross 기록을 보존하면서 전체 $0.50의 `정산+미확정+진행중 선예약` 및 stage gross/HTTP cap을 어떻게 제한할지 **Human의 새 계약 승인**이 필요하다. 아직 구현/변경하지 않았다.

O0는 **NOT_READY / HOLD**다. Human 의미 결정 → GT 보존/별도 기준 동결 → 예산·새 승인 manifest/재개 계약의 무과금 검증 → 명시적 유료 승인 → 첫 비교 → Human 검토 순서다.
전체880건이나 O0/O1 추가 반복은 자동 실행하지 않는다. Production Batch 기본 OFF 및 #55/#68 OPEN을 유지한다.
