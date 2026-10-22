# Issue #68 — Gate V2 동결 준비와 O0 무과금 리허설

## 현재 판정

- 기준 HEAD: `8ffb5aabc1df96e0a20d08b0f29eab89ac069807`, branch `feature/68-openai-poll-batch-verification`.
- 테스트 전용 미커밋 변경이다. Production/Prompt/Policy/Rule/Model/GT를 변경하지 않았다.
- **기존 Gate V1 BATCH_GATE_HOLD 유지, 실제 O0 미실행, Batch 기본 OFF.**
- 이번 단계의 Fake 리허설은 전송·장부·Kafka/DB 계약 검증이다. 모델 품질/실제 Token/청구비용/운영 효과가 아니다.
- 유료 호출/API Key 읽기/AWS/Commit/Push/PR/Issue 변경은 하지 않는다.
- 기존 Human GT, 모든 과거 Evidence/raw 및 private journal/claim/checkpoint는 보존한다.
- Primary: 별도 metadata와 원래 GT 점수의 분리, 전송 전 원자적 예약, Kafka→개별 DB Commit→Offset 정합성.
- Before/After 모델 품질/비용 비교: NOT_APPLICABLE. 실제 모델 응답을 새로 생성하지 않았다.

## Human 방향 적용

GT는 원래80건 그대로다. PROFANITY-14/15는 HIGH·엄격 보호, PROFANITY-16은 HIGH·문맥 의존,
SPAM-11/16은 HIGH·위험도 경계, SAFE-09와 SPAM-12는 기존 라벨을 유지한다.
이는 GT 변경이 아니라 별도 평가 메타데이터다.

명시적인 강한 욕설/직접 개인정보/고위험 금전 유인과 생략·친근한 호칭·비유를 구분했다.
문맥 의존은 위험 단어가 없다는 이유로 모든 정상 대화에 붙이는 분류가 아니다.
의미가 다의적인 호칭·경미한 평가·칭찬/농담/비유·불명확한 위해 예고를 동일 기준으로 분리했다.
PROFANITY-16만 Case ID 예외로 두지 않고 다른13건도 같은 메타데이터로 보고한다.

**기존 결과를 이미 알고 설계한 한계가 있다.** 새 블라인드 품질 평가라고 주장하지 않는다.
기존 오답 여부를 분류 조건으로 사용하지 않았고 원래 전체80 점수/분모를 그대로 보존한다.

## 별도 버전·해시

- 원래 GT: `moderation-policy-v2`, SHA256 `2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b`.
- Gate 초안: `issue68-service-gate-v2-proposal-1`.
- 별도 metadata: `issue68-policy-v2-review-metadata-1`.
- Metadata canonical SHA256: `a3a424323e5585da1b9a611281ac37174c60c4b946987e541c44ad5981cd40b5`.
- [전체80 metadata JSON](gate-v2-metadata-v1.json).
- 해시는 metadata version + 원래 GT hash + ID 정렬한 본문hash/profile/근거/필수Category/최소Risk로 계산한다.
- `requireFrozen()`와 단위 테스트가 두 해시를 확인한다. 예상치 못한 변경이면 O0 준비를 거절한다.
- 전체 Case 분류는 Critical25 / 문맥 의존14 / 위험도 경계2 / 일반39다.
- 새40건은 여전히 Human GT 미확정이므로 제외한다. 아래 목록은 AI가 Human 기준을 적용한 **동결용 제안**이며, 유료 실행 승인이 아니다.

## 전체80 Case별 분류

원문은 원래 `heldoutCasesPolicyV2()`를 참조한다. JSON에는 ID와 본문 SHA256만 두고 개인 연락처 모양의 합성 문자열도 복제하지 않았다.
PI-01~16의 Critical 최소Risk는 원래 MEDIUM이며 새 PI/HIGH 정책을 만들지 않았다.
PROFANITY-09/10도 강한 직접 모욕의 필수 최소Risk를 원래 MEDIUM으로 유지한다.

| Case ID | 분류 | 정책·표현 근거 | 필수 Category / 최소 Risk |
|---|---|---|---|
| HOLDOUT-SAFE-01 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-02 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-03 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-04 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-05 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-06 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-07 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-08 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-09 | 일반 | Human 기존 SAFE 유지: 단체 예약 연락처; 공개/개인 소유자를 추가 추정해 라벨을 바꾸지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-10 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-11 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-12 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-13 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-14 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-15 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-16 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-17 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-18 | 일반 | 예약/매장 공개 정보 또는 정상 대화; 직접 위험 표현 없음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-19 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-SAFE-20 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-01 | 일반 | 문장에 상대방을 향한 직접 모욕/적대 표현이 명시됨 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-02 | 엄격 보호 | 명시적 강한 욕설/약어/분리 표기 모욕; 최소 Risk는 원래 GT와 동일 | PROFANITY / HIGH |
| HOLDOUT-PROFANITY-03 | 일반 | 문장에 상대방을 향한 직접 모욕/적대 표현이 명시됨 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-04 | 일반 | 문장에 상대방을 향한 직접 모욕/적대 표현이 명시됨 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-05 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-06 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-07 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-08 | 엄격 보호 | 명시적 강한 욕설/약어/분리 표기 모욕; 최소 Risk는 원래 GT와 동일 | PROFANITY / HIGH |
| HOLDOUT-PROFANITY-09 | 엄격 보호 | 명시적 강한 욕설/약어/분리 표기 모욕; 최소 Risk는 원래 GT와 동일 | PROFANITY / MEDIUM |
| HOLDOUT-PROFANITY-10 | 엄격 보호 | 명시적 강한 욕설/약어/분리 표기 모욕; 최소 Risk는 원래 GT와 동일 | PROFANITY / MEDIUM |
| HOLDOUT-PROFANITY-11 | 일반 | 문장에 상대방을 향한 직접 모욕/적대 표현이 명시됨 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-12 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-13 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-14 | 엄격 보호 | Human 엄격 보호: 조건부 보복 또는 거주지 추적·방문; 기존 HIGH 유지 | PROFANITY / HIGH |
| HOLDOUT-PROFANITY-15 | 엄격 보호 | Human 엄격 보호: 조건부 보복 또는 거주지 추적·방문; 기존 HIGH 유지 | PROFANITY / HIGH |
| HOLDOUT-PROFANITY-16 | 문맥 의존 | 위해 행위/의도나 강한 적대감이 명시되지 않은 만남·각오 요구; Human 문맥 의존 지정 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-17 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-18 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-19 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PROFANITY-20 | 문맥 의존 | 경미한 평가/호칭/농담/칭찬·비유로 해석 가능하며 강한 적대감·위해를 직접 명시하지 않음 | 별도 Critical 요구 없음 |
| HOLDOUT-PI-01 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-02 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-03 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-04 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-05 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-06 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-07 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-08 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-09 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-10 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-11 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-12 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-13 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-14 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-15 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-16 | 엄격 보호 | 개인 전화/메일/계좌/메신저 ID가 직접 제시됨; PI Category와 최소 MEDIUM 보호 | PERSONAL_INFORMATION / MEDIUM |
| HOLDOUT-PI-17 | 일반 | 개인정보 제외 조건인 공개 사업장 연락처 또는 일반 숫자/예약번호 | 별도 Critical 요구 없음 |
| HOLDOUT-PI-18 | 일반 | 개인정보 제외 조건인 공개 사업장 연락처 또는 일반 숫자/예약번호 | 별도 Critical 요구 없음 |
| HOLDOUT-PI-19 | 일반 | 개인정보 제외 조건인 공개 사업장 연락처 또는 일반 숫자/예약번호 | 별도 Critical 요구 없음 |
| HOLDOUT-PI-20 | 일반 | 개인정보 제외 조건인 공개 사업장 연락처 또는 일반 숫자/예약번호 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-01 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-02 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-03 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-04 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-05 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-06 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-07 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-08 | 엄격 보호 | 투자 리딩방/고수익 부업방/확정 수익 정보방의 명시적 고위험 금전 유도 | SPAM / HIGH |
| HOLDOUT-SPAM-09 | 엄격 보호 | 투자 리딩방/고수익 부업방/확정 수익 정보방의 명시적 고위험 금전 유도 | SPAM / HIGH |
| HOLDOUT-SPAM-10 | 엄격 보호 | 투자 리딩방/고수익 부업방/확정 수익 정보방의 명시적 고위험 금전 유도 | SPAM / HIGH |
| HOLDOUT-SPAM-11 | 위험도 경계 | Human 위험도 경계 지정; 기존 SPAM/HIGH 유지, 비Critical 하향의 검토 신호 보존 확인 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-12 | 일반 | Human 기존 SPAM/MEDIUM 유지: 신메뉴 안내와 이용 요청의 확정된 홍보 경계 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-13 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-14 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-15 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-16 | 위험도 경계 | Human 위험도 경계 지정; 기존 SPAM/HIGH 유지, 비Critical 하향의 검토 신호 보존 확인 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-17 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-18 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-19 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |
| HOLDOUT-SPAM-20 | 일반 | 명시적 일반 상업 유인 또는 정책상 정상 추천/후기 경계를 적용 | 별도 Critical 요구 없음 |

## Gate V1/V2 병행 평가

`Issue68GateV2`는 기존 `Issue68EvaluationContract.gate/quality/pairedGate`와
`Issue68ReviewGateDraft`를 재사용한다. V1 함수/과거 결과는 변경하지 않았다.

- 원래 GT 기준 Result/Category/Risk exact, TP/FP/FN/TN, P/R/F1, Actionability는 전체80에서 계속 계산한다.
- 문맥 의존14건도 오답/FP/FN에서 제외하지 않는다. UNRESOLVED도 정상 판정/정답으로 대체하지 않는다.
- Review Target은 DB Commit 완료 후 FLAGGED+MEDIUM/HIGH다. 기대 대상은 GT FLAGGED+MEDIUM/HIGH이며 미완료도 분모에서 빼지 않는다.
- 관리자 Query의 회원별2+1→3 경계와 SAFE/LOW 누락은 기존 실제 MySQL 테스트4건을 그대로 재사용한다.
- 검토 신호 보존은 관리자 자동 검토/처리 완료/제재를 보장하지 않는다. 사용자 신고도 FN을 완전히 보완하지 않는다.

| 조건 | V2 준비 코드 |
|---|---|
| UNRESOLVED / DB 미완료 | HOLD |
| GT 검토 대상이 SAFE/LOW | 문맥 의존 여부와 무관하게 FN/검토 누락을 기록하고 HOLD |
| Critical Category 손실 / 최소 Risk 하향 | HOLD |
| 비Critical HIGH→MEDIUM | FLAGGED/Category/DB 검토 신호 유지 시 비차단 참고 사유 기록. Risk Exact 오답·V1 HIGH 하향은 그대로 |
| FP / Category mismatch | 보수적으로 Human 검토 HOLD. Native 공통 오류도 삭제하지 않음 |
| 그 밖의 Risk mismatch | Human 검토 HOLD |
| Provider/Mapping/Validation/DB/Offset/Usage/예산 오류 | 기술 오류로 분리, 후속 전송/단계 차단 |

문맥 의존을 비차단으로 보고하더라도 **검토 대상 누락은 차단**한다는 명시적 원칙을 우선했다.
이 때문에 PROFANITY-16 MEDIUM은 원래 Risk 오답/검토 유지로 분리되지만 SAFE는 FN/검토 누락 HOLD다.
이 해석보다 넓은 문맥 의존 FN 면제를 승인한 것으로 간주하지 않는다.

현재 보수적 실행 순서는 Native80 종료 → V2/기술 Gate → Poll80 → paired Gate다.
따라서 Native 자체 FP도 Native 종료 뒤 Human 검토 대상이 되어 Poll을 자동 시작하지 않는다.
기존 Native FP와 Batch 공통 오류를 보기 위해 첫160건을 하나의 제한된 평가 단위로 허용할지는
추가 Human 결정 없이 바꾸지 않았다. `paired()`는 새FP/Category regression과 공통FP/FN/Category/Risk 오류를 별도로 기록할 수 있다.

## O0 입력·요청 계약

`Issue68O0Plan`은 동일 Seed55 순서의 기존80건을 사용한다.
동일 Rule hit1건은 Provider에서 제외하고, 같은79건만 양쪽에서 처리한다.

- Native: 논리79회.
- Poll: 실제 poll/Rule miss 구성에 따라 논리8~79회. 최대10건, 1건이면 기존 Native.
- 합계 정상 논리87~158회, 단계 HTTP 상한 **632회**(158×SDK 최대4).
- 이 숫자는 Production Kafka×SDK×재균형 전체의 최악 상한이 아니다. O0 guard가 새 논리 재전달/중복을 거절하며,
  Provider 실패 후 다음 논리 요청을 막기 때문에 Production Retry 가능 횟수와 달라질 수 있다.
- Production Kafka Retry/DLT 설정/로직은 변경하지 않는다. 실험 guard에 차단된 처리의 DB/DLT/Offset은 별도 실패로 남겨야 한다.
- Publication 순서/partition 배치는 같지만 cross-partition poll 구성/호출 순서가 같다고 보장하지 않는다.
  실제 poll 구성, Case ID/중립ID 대응, body hash를 매 요청 기록한다.
- 기존 Model `gpt-4o-mini`, Policy v2, Prompt v3, Few-shot15, 단건/Batch 각각의 기존 Schema, 항목당 output cap128,
  기존 sampling 기본값을 유지한다. Provider sampling seed를 새로 설정하지 않는다.
- 요청 body 검사는 기존 `Issue68HttpBoundary`를 그대로 통과한다. SDK retry에서 body hash가 바뀌면 거절한다.
- Plan은 모드별 각 LLM Case를 한 번만 허용한다. 완료 Skip/Rule 처리를 재호출하지 않는다.
- 새로운 지시문, Q1, Checklist, size/time 수집기는 도입하지 않았다.

## 예산 제안의 정확한 의미

`Issue68Budget.startO0Preparation()`은 **Fake 준비용**이며 실제 private 파일에서 호출하지 않았다.
기존6개 이벤트와 정확히 같은 hash를 가진 비밀정보 없는 임시 fixture에서만 검증한다.
기존 Smoke의 `restoreSmoke()`/gross0.10 계약 및 실제 LiveSession 허용 범위는 그대로다.

1. 전체 보호 한도: **기존 정산 + 기존 UNKNOWN + 신규 정산 + 신규 UNKNOWN + 진행 중 예약 <= $0.50**.
2. O0 신규 한도: **이번 O0의 신규 정산 + UNKNOWN + 진행 중 예약 <= $0.10**.
   “신규0.10”을 증분 부담 한도로 해석했다. 기존 부담 $0.0195879를 없애는 것이 아니라 전체0.50에 계속 포함한다.
3. 기존 UNKNOWN **$0.0192768**, 기존 정산 **$0.0003111**, 기존 부담 **$0.0195879**,
   gross 이력 **$0.0579072**를 이월한다.
4. 매 HTTP exchange 전 같은 journal/OS lock 안에서 원자적으로 예약하고 fsync한다.
   128k input + 실제 요청 output cap의 최대액을 예약하며 Cache 할인으로 보호액을 낮추지 않는다.
5. 유효한 Usage만 정산한다. HTTP 오류/Usage 누락·손상/연결 실패/응답 유실/진행 중 종료는 예약을 유지한다.
6. gross는 모든 선예약의 합계 이력이다. 환급된 정상 예약까지 포함하므로 실제 비용 부담보다 크며,
   O0에서는 gross0.10을 전체 비용 한도로 오해하지 않는다. 기존 Smoke gross 기록은 바꾸지 않는다.
7. 한도가 부족하면 다음 HTTP 전에 차단한다. 완주를 위해 상한을 늘리거나 새 journal을 만들지 않는다.
8. 새 단계 marker와 별도 CREATE_NEW/fsync claim을 만든다. 중복 프로세스/재시작/같은 root의 journal rollback으로 재개하지 않는다.
   재시작은 회계만 복원하는 audit 모드로, reserve는 막혀 있다.
9. 실제 Live 연결은 고정 repository root/journal과 새로운 승인 manifest에 묶어야 한다.
   임시 fixture 경로를 받는 준비용 API를 실제 유료 실행 권한으로 사용하면 안 된다.
   journal/claim 전체를 고의로 복제·삭제하는 공격, 다른 프로세스의 계정 사용·가격 변경·세금 등은 이 내부 장부의 보장 범위가 아니다.

**완주 보장과 비용 차단은 다르다.** 정상 가정의 과거 동결 forecast는 첫 paired160건에서 약
$0.02623005~$0.03682290(Retry 미포함, 단가/Tokenizer/출력cap 가정)지만 실제 Provider Usage/청구액이 아니다.
재확인용 모델/가격 API나 API Key를 읽지 않았다.
Usage 불명 최대 Batch 예약5회면 새 부담 $0.09984가 되어 여섯 번째는 막힌다.
일부 정상 요청을 처리했더라도 Retry/불명 Usage 때문에 Native80 또는 Poll80을 끝내지 못할 수 있다.
정확한 외부 청구/계정 전체 상한을 증명했다고 표현하지 않는다.

## 변경 및 검증

기존 테스트 전용 `Issue68Budget`(별도 O0 회계 모드), `Issue68HttpBoundary`(loopback 준비 factory)만 확장했다.
새 `Issue68GateV2`, `Issue68O0Plan`, 단위 테스트3개 및 Kafka/MySQL 리허설1개를 추가했다.
원래 Gate V1, GT, 기존 Smoke 승인/claim/예외 결과, Production은 그대로다.

- 신규 테스트20건: Gate6 / O0 Budget·Retry9 / 입력·단계4 / Kafka·MySQL1.
- 기존 무과금 관련120건에는 이전 관리자 Query/MySQL11건과 Smoke/Usage/재개 회귀가 함께 포함된다.
- Fake 160건 리허설에서 실제 Consumer/Adapter/SDK/JPA afterCommit/Offset, gzip SDK/HTTP Usage 일치,
  요청별 단일 Usage/메시지별 Token 미복제, 최종 Lag/DLT/조기commit0을 확인한다.
- 과거 성공/실패를 다시 계산하거나 과거 Live HOLD를 PASS로 소급 변경하지 않는다.
- 최종 실행 명령/건수/환경/source SHA와 Fake 원본 추출은 신규 검증 JSON에 별도로 기록한다.

## 최종 무과금 실행 결과 (2026-10-21)

- [검증 결과·실행 명령·테스트 source SHA256](o0-preparation-verification.json)
- [최종 Fake Kafka/MySQL 원본 필드 추출](o0-fake-rehearsal-extract.json)
- Java17 / Gradle9.5.1 / Kafka7.7.1 / MySQL8.4 Testcontainers. 기존 DB를 사용하지 않았다.
- 신규20건: 실패/오류0. 관련120건: skipped1(유료 Smoke), 실패/오류0.
- 전체 root1,280 + Lambda11 = **1,291 tests / 87 skipped / failures0 / errors0**.
- `env -u OPENAI_API_KEY -u ISSUE68_LIVE_APPROVAL ./gradlew test build --rerun-tasks --console=plain --no-daemon`
  → **BUILD SUCCESSFUL in 4m48s, 12 tasks executed**. root/Lambda 모두 재실행했다.
- 이후 sandbox의 Gradle version 조회는 wrapper lock 권한으로 실패했다. 위 성공한 전체 build와는 별도 진단이며,
  Gradle 버전은 wrapper 설정을 근거로 기록했다.

| 최종 Fake 리허설 | Native | Poll-Batch |
|---|---:|---:|
| 입력/DB 완료 | 80/80 | 80/80 |
| Rule / LLM 메시지 | 1/79 | 1/79 |
| 논리 요청 / HTTP | 79/79 | 8/8 |
| 실제 LLM 요청 크기 | 1건 ×79 | 10건 ×7, 9건 ×1 |
| 최종 Lag / DLT / 조기 Commit 위반 | 0/0/0 | 0/0/0 |
| 저장 실패 / Transaction 위반 / UNRESOLVED | 0/0/0 | 0/0/0 |

각 Case의 JPA afterCommit 및 Kafka Offset 안전성을 확인했다. 추출 JSON의
`offsetCommitObservations`는 Offset observer 횟수이며 개별 DB transaction 수가 아니다.
SDK/HTTP Usage가 일치하고 모든87 HTTP가 SETTLED였다. Batch 메시지 DB Token은 NULL로 유지했다.
GT와 일치하도록 구성한 Fake 응답이므로 정확도100%나 요청 감소를 실제 AI 품질/효율 성과로 해석하지 않는다.

Fake 장부의 신규 부담은 $0.013641, 과거 fixture 이월 포함 부담은 $0.0332289,
gross 예약 이력은 $1.7404416이었다. 이는 **회계 검증용 합성 Usage**이며 새 API 지출이 아니다.
gross와 환급 후 부담이 다르다는 것을 확인했으며 실제 private 장부는 전혀 갱신하지 않았다.

작업 전 해시76개를 재대조했다. 승인된 테스트 Harness2개(`Issue68Budget`, `Issue68HttpBoundary`)만 변경됐고,
과거 Evidence38개와 private journal/claim/checkpoint17개를 포함한74개는 byte hash가 동일했다.
나머지 새 테스트6개 및 새 Evidence4개만 이번 단계에 추가했다.
tracked/staged Diff는 없으며 미커밋/미추적 상태를 유지한다. 기존 GT/Production/설정은 변경하지 않았다.

## 최종 준비 판정

**현재 즉시 Live 실행 불가.** Fake 계약은 검증하되 기존 Live 경계는 Smoke의 Native1/Batch1·고정2개 Case만 허용하며,
현재 소비된 승인 claim을 우회하지 않는다. 새 O0 승인 ID를 허용하거나 API Key를 읽는 경로는 이번에 추가하지 않았다.

유료 승인 전 최소 확인:
- 위80건 metadata hash와 Critical 보호 속성/문맥 의존 목록을 이번 평가의 고정 계약으로 승인하는가?
- Native 자체 FP가 있어도 첫 Native80/Poll80을 하나의 비교 단위로 실행할 것인가,
  아니면 현재의 Native80 종료 후 HOLD를 유지할 것인가?
- 신규0.10을 증분 부담 한도로 하고 기존 장부를 전체0.50에 이월하는 제안 및 HTTP632/모드79 상한을 승인하는가?

승인 후에도 테스트 전용 LiveSession/HttpBoundary/OpenAiContext에 새 manifest/고정 경로/실제 HTTPS 요청 범위의 최소 opt-in 연결과
동일 차단 조건의 무과금 검증이 필요하다. 이를 완료하기 전에는 API를 전송하지 않는다.
자동 재개/O0 추가 반복/O1/나머지880건은 별도 승인 없이 실행하지 않는다.
