# JPA 낙관적 락으로 AI 분석 결과의 갱신 유실 막기

## 문제

채팅 메시지 ID는 고유하다. 하지만 같은 메시지를 한 번만 보낸다는 사실만으로 AI 분석 저장이 항상 순서대로 끝나지는 않는다. 미래의 Kafka 재시도·중복 배달에서는 성공 분석 경로와 최종 실패 기록 경로가 같은 `ANALYSIS_FAILED` 행을 동시에 읽을 수 있다.

DB UNIQUE(`chat_message_id`)는 둘이 새 행을 만들려는 경우에는 막아 준다. 이미 있는 행을 각자 UPDATE하는 경우에는 막지 못한다. 그래서 늦은 실패 UPDATE가 먼저 저장된 SAFE/FLAGGED 결과를 덮을 수 있다. 이것이 갱신 유실이다.

## 선택: JPA `@Version` 낙관적 락

`ChatModeration`에 `@Version Long version`을 둔다. JPA UPDATE에는 읽었을 때의 version이 조건으로 포함되고, 저장 성공 시 version이 증가한다. 다른 요청이 먼저 저장했다면 늦은 UPDATE는 영향 행 수 0이 되어 `OptimisticLockingFailureException`으로 실패한다.

이 방식은 OpenAI 호출 전체에 DB 락을 잡지 않는다. 외부 호출은 수백 ms 이상 걸릴 수 있으므로, 비관적 락을 계속 보유하면 같은 메시지 처리뿐 아니라 DB connection·lock 대기까지 길어진다. 낙관적 락은 짧은 저장 구간에서만 충돌을 확인한다.

## 충돌 뒤의 정책

| 최신 DB 상태 | 처리 |
|---|---|
| `SAFE` 또는 `FLAGGED` | 성공 결과가 이겼으므로 종료한다. |
| `ANALYSIS_FAILED` + 성공 분석 저장 충돌 | 이미 받은 AI 응답으로 DB 저장만 1회 재시도한다. OpenAI는 다시 부르지 않는다. |
| 최종 실패 저장 충돌 | 최신 행을 덮지 않고 종료한다. |

여기서 "1회 재시도"는 Kafka 작업 재시도가 아니다. 저장 충돌만 해결하는 짧은 DB 재시도다. OpenAI 호출 실패의 전체 재시도와 DLT는 #59 Kafka Consumer가 소유한다.

## 검증

H2/JPA 테스트에서 같은 실패 행을 두 개의 별도 트랜잭션으로 읽었다. 첫 번째를 FLAGGED로 저장한 뒤 두 번째 stale 객체를 저장하면 `OptimisticLockingFailureException`이 발생했고, 마지막 조회 결과는 FLAGGED였다. 서비스 단위 테스트는 INSERT 충돌, 성공 저장 충돌의 1회 DB 재시도, 최종 실패 충돌을 추가로 검증하며 성공 분석 중 Provider 호출 수는 1회임을 확인한다.

## 면접용 한 문장

"UNIQUE는 중복 생성, `@Version`은 stale UPDATE를 막습니다. 외부 AI 호출에는 락을 잡지 않고 저장 시점에만 낙관적 락으로 충돌을 검출한 뒤, 성공 결과 우선 규칙으로 처리했습니다."
