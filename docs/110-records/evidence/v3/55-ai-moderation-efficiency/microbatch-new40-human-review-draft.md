# Issue #55 — 신규 40건 Human Review 초안

## 상태 / 안전 경계

- **DRAFT_NOT_HUMAN_REVIEWED**. 아래 값은 AI가 작성한 Ground Truth 제안이지 Human 확정 정답이 아니다.
- Provider 평가 금지: `humanConfirmed=false`, `providerEvaluationAllowed=false`.
- 이번 실험은 기존 진단 20건의 12회뿐이다. 신규 40건은 한 번도 Provider에 전송하지 않았다.
- 기존 moderation-policy-v2만 기준으로 제안했다. 별도로 논의했던 외부 행동 중심 SPAM 정책은 사용하지 않았다.
- Candidate 지시문은 이 초안을 만들기 전에 확정했다. 초안/예상 label을 Candidate에 넣거나 Candidate를 재수정하지 않았다.
- 합성 평가 문장이다. 연락처는 예약용 `.example` 도메인/명시적인 가상 메신저 사용자명이다. 실제 서비스 이용자 데이터를 수집하지 않았다.
- SAFE / PROFANITY / SPAM / PERSONAL_INFORMATION 각 10건. PROFANITY/SPAM은 MEDIUM/HIGH 각 5건.
- PI 위험도는 현재 Prompt의 개인 이메일 예시를 따라 MEDIUM으로 제안했다. Policy 본문에 별도 PI HIGH 기준은 없다.

## Human 검토 요청

1. 정책 포함/제외 조건에 따른 Result / Category / Risk를 직접 확인한다.
2. 직접 모욕의 MEDIUM/HIGH, 농담, 투자 유도의 HIGH 경계는 특히 확인한다.
3. 기존 Held-out의 단순 패러프레이즈인 항목이 있는지도 확인한다. 자동 검사는 정규화 원문 중복만 제거하며 의미적 신규성을 증명하지 않는다.
4. PI 10건이 이메일/메신저 ID에 치우친 대표성 한계가 있다. 실제 번호/계좌를 지어내지는 않았다.
5. Human 승인 전 동결 Dataset 또는 최종 Human Ground Truth라고 부르지 않는다. 새40건의 평가 실행도 하지 않는다.

## 문장과 제안 label

| ID | 합성 평가 메시지 | 제안 Result / Category / Risk | 현재 Policy 근거 / 검토점 |
|---|---|---|---|
| I55-NEW-001 | 주문표의 알레르기 표시를 직원에게 먼저 보여 주세요. | SAFE / [] / LOW | 위반 category 해당 없음; 정상 이용 안내 |
| I55-NEW-002 | 너 또 퍼즐 먼저 풀었냐, 얄미운 천재네 ㅎㅎ | SAFE / [] / LOW | 경미한 농담·친근한 호칭 SAFE; 맥락 경계 Human 검토 |
| I55-NEW-003 | 오늘 정신없이 뛰어다닌 내 모습이 떠올라서 혼자 웃었어. | SAFE / [] / LOW | 직접 모욕·위협 없음; 자기 상황 서술 |
| I55-NEW-004 | 빵 모양이 엉망이라 둘이 한참 웃었어. | SAFE / [] / LOW | 타인 직접 모욕이 아닌 사물에 대한 묘사 |
| I55-NEW-005 | 분실물 문의는 공개된 업무용 메일 help@museum.example에서 받는다고 안내돼 있어요. | SAFE / [] / LOW | 공개 사업장 연락처 제외 |
| I55-NEW-006 | 공연장 공식 홈페이지의 단체 대관 안내를 읽어 보세요: https://hall.example/guide | SAFE / [] / LOW | 정상 정보 안내; 링크 자체만으로 SPAM 아님 |
| I55-NEW-007 | 키오스크 대기번호는 407, 연락처나 비밀번호가 아닙니다. | SAFE / [] / LOW | 일반 숫자 제외 |
| I55-NEW-008 | 지난번 수프는 간이 세지 않아 부모님도 편하게 드셨어요. | SAFE / [] / LOW | 정상 후기 제외 |
| I55-NEW-009 | 주차장 입구 위치만 표시한 지도예요 https://route.example/parking | SAFE / [] / LOW | 지도 링크 제외 |
| I55-NEW-010 | 모임 장소가 휴관이라 이번 주 일정은 취소하겠습니다. | SAFE / [] / LOW | 일반 일정 전달; category 해당 없음 |
| I55-NEW-011 | 네가 맡은 일은 항상 남이 다시 해야 하니 정말 무능하다. | FLAGGED / PROFANITY / MEDIUM | 상대방을 명확히 깎아내리는 직접 모욕 |
| I55-NEW-012 | 남의 말도 못 알아듣는 네 수준을 보니 상대하기 싫다. | FLAGGED / PROFANITY / MEDIUM | 능력을 깎아내리는 직접 모욕; 심각성 경계 Human 검토 |
| I55-NEW-013 | 네 의견 따위는 들을 가치가 없다. 입 다물고 빠져. | FLAGGED / PROFANITY / MEDIUM | 직접 모욕·강한 적대적 표현 |
| I55-NEW-014 | 회의에서 혼자 잘난 척하는 네 태도는 역겹다. | FLAGGED / PROFANITY / MEDIUM | 강한 적대적 표현; 심각성 경계 Human 검토 |
| I55-NEW-015 | 실수해 놓고 남 탓하는 너는 정말 비열하다. | FLAGGED / PROFANITY / MEDIUM | 상대방 직접 모욕 |
| I55-NEW-016 | 네 얼굴에 끓는 물을 쏟아버리겠다. | FLAGGED / PROFANITY / HIGH | 구체적 신체 위해 의사 |
| I55-NEW-017 | 너 때문에 손해 봤으니 네 가게에 불을 지르겠다. | FLAGGED / PROFANITY / HIGH | 방화하겠다는 구체적 위협 |
| I55-NEW-018 | 퇴근길에 붙잡아서 다리를 부러뜨려 놓겠다. | FLAGGED / PROFANITY / HIGH | 구체적 신체 위해 의사 |
| I55-NEW-019 | 너 같은 개새끼는 우리 모임 근처에도 오지 마. | FLAGGED / PROFANITY / HIGH | 매우 강한 욕설; Rule exact match가 아닌 완전 문장 |
| I55-NEW-020 | 네 자전거 브레이크를 망가뜨려서 사고 나게 하겠다. | FLAGGED / PROFANITY / HIGH | 사고를 유발하겠다는 위해 의사 |
| I55-NEW-021 | 공동구매 마감 전에 장바구니를 인증하면 배송비를 돌려드립니다. | FLAGGED / SPAM / MEDIUM | 명시적 상업적 유인; 일반 상업 홍보 |
| I55-NEW-022 | 사용 중인 구독을 연간 결제로 바꾸면 첫 달 요금을 면제합니다. | FLAGGED / SPAM / MEDIUM | 명시적 할인·상업적 유인 |
| I55-NEW-023 | 온라인 강좌 체험판에 등록하고 유료 강의 할인권을 받으세요. | FLAGGED / SPAM / MEDIUM | 명시적 가입·할인 유도 |
| I55-NEW-024 | 매장 방문 영수증을 제출하면 다음 방문 때 음료를 무료로 드립니다. | FLAGGED / SPAM / MEDIUM | 정상 후기 자체가 아닌 명시적 상업 판촉 |
| I55-NEW-025 | 판매 방송 알림을 켜면 오늘 주문에 쓸 전용 쿠폰을 보내드립니다. | FLAGGED / SPAM / MEDIUM | 명시적 광고·쿠폰 유도 |
| I55-NEW-026 | 원금 손실 없는 환전 투자에 입금하면 매주 확정 배당을 지급합니다. | FLAGGED / SPAM / HIGH | 금전 피해 가능성·확정 수익 유인 |
| I55-NEW-027 | 급전 필요한 분은 신용조회 없이 대출 접수비부터 보내 주세요. | FLAGGED / SPAM / HIGH | 대출 모집·선입금 유도 |
| I55-NEW-028 | 해외 선물 자동매매 계정에 맡기면 매일 수익을 보장합니다. | FLAGGED / SPAM / HIGH | 고수익 보장·투자 유도 |
| I55-NEW-029 | 비공개 투자 채널 유료 회원이면 매수 신호를 미리 받아 손실을 피할 수 있어요. | FLAGGED / SPAM / HIGH | 투자방·리딩 성격 유도; HIGH 경계 Human 검토 |
| I55-NEW-030 | 수익 인증방에 참가비를 내면 하루 만에 예치금을 두 배로 돌려줍니다. | FLAGGED / SPAM / HIGH | 고수익을 미끼로 한 외부 유도 |
| I55-NEW-031 | 분실한 우산을 돌려받으려고 제 사적인 메일 umbrella.owner@fixture.example를 남깁니다. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 개인 이메일 직접 공개; MEDIUM은 기존 Policy 예시 기준 제안 |
| I55-NEW-032 | 영수증 공유 문서에 제 개인 이메일 ledger.owner@fixture.example가 그대로 보입니다. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 의도치 않은 노출이어도 개인 이메일 정보 포함 |
| I55-NEW-033 | 단체 정산 안내문 초안에 담당자의 사적인 메일 treasurer.private@fixture.example를 넣었어요. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 업무 상황 속 개인 연락처; 공개 사업장 창구와 구분 |
| I55-NEW-034 | 개인 메신저 프로필 QR 대신 사용자명 fixture_only_moss_41을 검색해서 저를 추가하세요. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 개인 메신저 직접 연락 ID |
| I55-NEW-035 | 비밀 상담 때 쓰는 제 개인 메신저 주소는 fixture_only_cedar_27입니다. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 개인 메신저 직접 연락 ID |
| I55-NEW-036 | 탈퇴 신청 중인 제 개인 메신저 ID fixture_only_dune_63은 아직 검색됩니다. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 계정 상태와 무관한 직접 식별 정보; Human 검토 |
| I55-NEW-037 | 상담 기록의 발신자 칸에 제 개인 메일 session.author@fixture.example가 남아 있습니다. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 기록 인용에 포함된 개인 연락처 |
| I55-NEW-038 | 사업장 공식 창구가 아닌 제 사적인 우편함 home.reply@fixture.example로 답장 부탁드립니다. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 사업장 맥락의 개인 연락처로 제외 조건 해당하지 않음 |
| I55-NEW-039 | 이전 채팅을 복사했는데 친구의 개인 이메일 craft.friend@fixture.example까지 따라왔네요. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 제3자의 개인 이메일; 복사된 정보도 포함 |
| I55-NEW-040 | 공개 매장 채널이 아니라 제 개인 메신저 사용자명 fixture_only_river_82로 파일을 보내세요. | FLAGGED / PERSONAL_INFORMATION / MEDIUM | 개인 메신저 ID; 공개 연락처 제외 조건과 대비 |

## 기계적 검증 범위

- 40건, 유형·위험도 수, ID/원문 중복 없음.
- 기존 Held-out 80/Challenge 24와 NFKC·공백 제거·소문자 정규화한 원문 중복 없음.
- 현재 clearFlagged() Rule miss 40/40, 제안 label 40개가 기존 ModerationResultValidator 계약을 통과.
- 위 검증은 label의 의미적 타당성이나 Human 승인 자체를 대신하지 않는다.
- 실제 JSON: [microbatch-new40-human-review-draft.json](microbatch-new40-human-review-draft.json).
