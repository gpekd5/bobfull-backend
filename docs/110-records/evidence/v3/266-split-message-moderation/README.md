# Issue #266 Split Message Moderation

## 결론

`ADOPT_RULE_ONLY_SPLIT_CONTEXT`를 확정한다. `messageId` Kafka key는 유지한다. DB Context는 same room/sender, current 이하, `createdAt + id` 정렬로 재구성되며, 명백한 욕설 결합은 Rule로 탐지한다. Context LLM은 공개 사업장 분할 번호를 개인정보로 FLAGGED해 production 경로에서 제거했다.

## Provider 결과

2026-08-14 local opt-in OpenAI 1회 실행, 기존 Policy v2와 output cap 128을 사용했다.

| Case | Result |
|---|---|
| 시→발→아 | FLAGGED / PROFANITY / MEDIUM |
| 병→신 | FLAGGED / PROFANITY / MEDIUM |
| 시→간 | SAFE |
| 죽→먹고 싶다 | SAFE |
| 개인 연락처 Split | FLAGGED / PERSONAL_INFORMATION / MEDIUM |
| 공개 사업장 연락처 Split | FLAGGED / PERSONAL_INFORMATION / MEDIUM (FP) |

핵심 Split Result/Category 탐지는 2/2이며, 정상 공개 사업장 연락처에서 신규 FP 1건이므로 DB Context LLM 품질 기준은 통과하지 못했다. Rule Fast Path는 Provider 호출 없이 `시발`, `병신`을 FLAGGED한다. 애매한 Context는 기존 단건 Provider 입력으로 처리한다.

## DB Context 계약

`chatRoomId`, `senderMemberId`, 30초 후보 window, 최근 5건, `createdAt DESC + id DESC`를 조회한 뒤 서비스에서 ASC로 복원한다. current 이후 메시지는 JPQL 조건으로 제외한다. Repository 테스트는 M101, M102, M103이 저장돼도 M102 Context에 M103이 포함되지 않음을 검증한다.

30초/5건은 #251의 최소 후보값이며, 이번 핵심 평가만으로 일반화된 최적값을 확정하지 않는다. Context LLM이 FP를 내므로 더 넓은 window/N 비교는 진행하지 않았다.

## Trade-off

`chatRoomId` key는 방 순서를 유지하지만 특정 채팅방에 메시지가 몰리면 한 partition에 부하가 집중된다. `messageId` key는 병렬 처리를 늘릴 수 있지만 방 순서를 포기한다. #266은 Kafka 순서가 아닌 DB 이력으로 명백한 Split Rule 입력을 복원한다.

## 검증

- `ChatModerationServiceTest`, `ChatMessageRepositoryTest` 통과
- 실제 Provider 6 Case 통과(분류 결과는 위 표; 공개 사업장 FP 관측)
- 기존 Kafka Consumer Retry/DLT 구현은 변경하지 않았으며, 이번 변경은 messageId key를 변경하지 않는다.

## 한계

Provider 결과는 단일 실행이다. 공개 사업장 번호 FP 때문에 Context LLM production 채택 근거는 없다.

## Human E2E 후속

Human E2E에서 단건 `죽`과 `010`이 각각 위협·개인정보로 과대판정된 것을 확인했다. 이는 Split Context가 아닌 단건 LLM boundary 문제였다. Policy 의미는 유지한 채 Few-shot에 두 불완전 조각의 SAFE boundary를 추가했고, local Provider 재검증에서 `죽`, `010`, `시`, `간`은 모두 SAFE였다. 반복 문자·중간 noise Split 우회에는 bounded canonicalization을 적용해 기존 명백 욕설과 정확히 일치할 때만 Rule Fast Path로 FLAGGED한다.
